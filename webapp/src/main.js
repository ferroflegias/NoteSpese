import initSqlJs from 'sql.js';
import wasmUrl from 'sql.js/dist/sql-wasm.wasm?url';
import './style.css';

import { kvGet, kvSet, requestPersistentStorage } from './storage.js';
import { $, esc, toast, setStatus, openDialog, confirmDialog, shareOrDownload } from './ui/common.js';
import { attachmentPicker } from './ui/attachmentPicker.js';
import { saveAttachment, readAttachment, deleteAttachment, isLocalOnly, uploadLocalAttachments } from './attachments.js';
import { isPdfBytes } from './imageTools.js';
import { EXCEL_MODES, isXlsx, fillExcelTemplate, excelFileName, templateSheetNames } from './core/excelExport.js';
import * as dropbox from './dropbox.js';
import { listBackups, uploadBackup, downloadBackup } from './backupService.js';
import {
  createDatabase, openDatabase, countSpese, listSpese, speseForMonth, getSpesa,
  insertSpesa, updateSpesa, updateAttachmentPath, deleteSpesa, exportDatabase, DbError
} from './core/dbcore.js';
import {
  CATEGORY_OPTIONS, PAYMENT_OPTIONS, RISTORANTI, PAYMENT_CC, TELEPASS,
  categoryKey, paymentLabel, monthTotals, categoryFromOcr, paymentFromOcr
} from './core/categories.js';
import {
  todayIsoDate, isValidIsoDate, parseAmount, formatAmount, formatDateTime,
  sanitizeDeviceName, MONTH_NAMES
} from './core/formats.js';
import {
  restoreWarnings, hasUnsavedChanges, newerBackupToRestore, attachmentKind
} from './core/backups.js';

const BUILD_APP_KEY = (import.meta.env.VITE_DROPBOX_APP_KEY ?? '').trim();
const APP_VERSION = import.meta.env.VITE_APP_VERSION ?? 'dev';

const state = {
  SQL: null,
  db: null,
  tab: 'spesa',
  account: '',
  appKey: BUILD_APP_KEY,
  deviceName: 'iPhone',
  sync: { lastLocalChangeMs: 0, lastSyncMs: 0 },
  newerBackup: null,
  busy: false
};

// ---------- utilità ----------

async function busy(action) {
  if (state.busy) return;
  state.busy = true;
  document.body.classList.add('busy');
  try {
    return await action();
  } finally {
    state.busy = false;
    document.body.classList.remove('busy');
  }
}

// ---------- database locale ----------

async function persistDb() {
  await kvSet('db', exportDatabase(state.db));
}

/** Da chiamare dopo ogni modifica alle spese fatta su questo dispositivo. */
async function afterLocalChange() {
  await persistDb();
  state.sync.lastLocalChangeMs = Date.now();
  await kvSet('sync', state.sync);
  renderBanners();
}

async function markSynced(atMs) {
  state.sync.lastSyncMs = atMs;
  await kvSet('sync', state.sync);
  state.newerBackup = null;
  renderBanners();
}

async function loadLocalState() {
  state.SQL = await initSqlJs({ locateFile: () => wasmUrl });
  const bytes = await kvGet('db');
  try {
    state.db = bytes ? openDatabase(state.SQL, bytes) : createDatabase(state.SQL);
  } catch {
    state.db = createDatabase(state.SQL);
  }
  state.sync = { lastLocalChangeMs: 0, lastSyncMs: 0, ...(await kvGet('sync')) };
  state.deviceName = (await kvGet('deviceName')) || 'iPhone';
  state.appKey = BUILD_APP_KEY || (await kvGet('appKey')) || '';
  state.account = await dropbox.connectedAccount();
}

// ---------- banner (backup da ripristinare / modifiche non salvate) ----------

function renderBanners() {
  const el = $('#banners');
  const parts = [];
  if (state.newerBackup) {
    const b = state.newerBackup;
    parts.push(`
      <div class="banner info">
        <div>📥 C'è un backup più recente${b.deviceName ? ` di <b>${esc(b.deviceName)}</b>` : ''}
        (${esc(formatDateTime(b.modified))}): ripristinalo prima di iniziare.</div>
        <button class="btn small" data-banner-restore>Ripristina</button>
      </div>`);
  }
  if (state.account && hasUnsavedChanges(state.sync)) {
    parts.push(`
      <div class="banner warn">
        <div>🟠 Modifiche non ancora salvate in un backup.</div>
        <button class="btn small" data-banner-backup>Backup ora</button>
      </div>`);
  }
  el.innerHTML = parts.join('');
  $('[data-banner-restore]', el)?.addEventListener('click', () => requestRestore(state.newerBackup));
  $('[data-banner-backup]', el)?.addEventListener('click', () => doBackup());
}

async function checkNewerBackup() {
  if (!state.account) return;
  try {
    const backups = await listBackups();
    state.newerBackup = newerBackupToRestore(backups, state.sync);
  } catch {
    state.newerBackup = null; // offline: nessun avviso
  }
  renderBanners();
}

// ---------- backup e ripristino ----------

async function doBackup() {
  if (!state.account) return toast('⚠️ Collega prima Dropbox (scheda Settings).', 'error');
  await busy(async () => {
    try {
      toast('Backup su Dropbox in corso...');
      const startedAt = Date.now();
      const savedAt = await uploadBackup(exportDatabase(state.db), state.deviceName);
      await markSynced(Math.max(startedAt, savedAt));
      toast("🎉 Backup salvato nella cartella 'Backups' su Dropbox!", 'ok');
    } catch (e) {
      toast(`❌ Backup non riuscito: ${e.message}`, 'error');
    }
    if (state.tab === 'settings') renderTab();
  });
}

async function requestRestore(backup) {
  if (!backup) return;
  let backups;
  try {
    backups = await listBackups();
  } catch (e) {
    return toast(`❌ Impossibile leggere i backup: ${e.message}`, 'error');
  }
  const warnings = restoreWarnings({
    syncState: state.sync,
    backups,
    chosen: backup,
    hasLocalData: countSpese(state.db) > 0
  });
  if (warnings.length > 0) {
    const ok = await confirmDialog(
      '⚠️ Ripristino rischioso',
      `<p>Backup scelto: <b>${esc(backup.name)}</b></p>
       <ul>${warnings.map((w) => `<li>${esc(w)}</li>`).join('')}</ul>
       <p class="muted">Prima del ripristino i dati attuali vengono comunque salvati nella cartella 'Backups_PreRipristino'.</p>`,
      'Ripristina comunque'
    );
    if (!ok) return;
  }
  await performRestore(backup);
}

/**
 * Sostituisce i dati locali con [backup]. Prima viene caricata su Dropbox una copia di
 * sicurezza dei dati attuali: se non riesce, il ripristino viene annullato.
 */
async function performRestore(backup) {
  await busy(async () => {
    try {
      if (countSpese(state.db) > 0) {
        toast('Copia di sicurezza dei dati attuali su Dropbox...');
        try {
          await uploadBackup(exportDatabase(state.db), state.deviceName, { safety: true });
        } catch (e) {
          toast(`❌ Impossibile salvare la copia di sicurezza: ripristino annullato, nessun dato modificato. (${e.message})`, 'error');
          return;
        }
      }
      toast('Scaricamento e ripristino del backup...');
      const bytes = await downloadBackup(backup.path);
      const restoredAt = Date.now();
      const restored = openDatabase(state.SQL, bytes, { requireExpenses: true });
      state.db.close();
      state.db = restored;
      await persistDb();
      await markSynced(restoredAt);
      toast(`🎉 Database ripristinato (${countSpese(state.db)} spese). I dati precedenti sono in 'Backups_PreRipristino'.`, 'ok');
    } catch (e) {
      const reason = e instanceof DbError ? e.message : `Errore: ${e.message}`;
      toast(`❌ Ripristino non riuscito. ${reason}`, 'error');
    }
    renderTab();
  });
}

async function showRestoreList() {
  if (!state.account) return toast('⚠️ Collega prima Dropbox per recuperare i backup.', 'error');
  let backups;
  await busy(async () => {
    try {
      backups = await listBackups();
    } catch (e) {
      toast(`❌ Impossibile leggere i backup: ${e.message}`, 'error');
    }
  });
  if (!backups) return;
  if (backups.length === 0) return toast("Nessun backup trovato nella cartella 'Backups' su Dropbox.");
  let html = '';
  backups.forEach((b, i) => {
    if (b.isSafetyBackup && !backups[i - 1]?.isSafetyBackup) {
      html += '<h4>🛟 Copie di sicurezza (fatte prima di un ripristino)</h4>';
    }
    html += `<button class="list-item" data-index="${i}">
      <span><b>${esc(formatDateTime(b.modified))}</b> ${b.deviceName ? `· ${esc(b.deviceName)}` : ''}</span>
      <small>${esc(b.name)} (${Math.round(b.size / 1024)} KB)</small>
    </button>`;
  });
  const dialog = openDialog(`
    <h3>Seleziona il backup da ripristinare</h3>
    <p class="muted">⚠️ Il ripristino sostituisce i dati di questo dispositivo con quelli del backup.</p>
    <div class="list">${html}</div>
    <div class="dialog-actions"><button class="btn secondary" data-close>Annulla</button></div>`);
  dialog.addEventListener('click', (e) => {
    const item = e.target.closest('[data-index]');
    if (!item) return;
    dialog.close();
    requestRestore(backups[Number(item.dataset.index)]);
  });
}

// ---------- allegati ----------

/** Salva l'allegato scelto (Dropbox o, se non raggiungibile, su questo dispositivo). */
async function storeAttachment(picked, dataSpesa) {
  const saved = await saveAttachment(picked.bytes, {
    isPdf: picked.isPdf,
    dataSpesa,
    dropboxConnected: Boolean(state.account)
  });
  return saved;
}

/** OCR della foto o del PDF e compilazione del form (come nell'app Android). */
async function runOcr(file, isPdf, form, root) {
  setStatus(root, '🔍 Analisi OCR in corso...');
  let res;
  try {
    const { analyzeReceipt } = await import('./ocr.js');
    res = await analyzeReceipt(file, { isPdf, onProgress: (msg) => setStatus(root, msg) });
  } catch (e) {
    return setStatus(root, `⚠️ OCR non riuscito: ${e.message ?? e}`, 'error');
  }
  if (!res) return setStatus(root, `⚠️ Nessun testo rilevato ${isPdf ? 'nel PDF' : 'nella foto'}.`, 'error');
  form.data.value = res.dataStr;
  form.destinazione.value = res.destinazione;
  if (res.importo > 0) form.importo.value = formatAmount(res.importo).replace('.', ',');
  const cat = categoryFromOcr(res.categoriaSuggerita);
  const pay = paymentFromOcr(res.pagamentoSuggerito);
  form.querySelector(`input[name=categoria][value="${CSS.escape(cat)}"]`).checked = true;
  form.querySelector(`input[name=pagamento][value="${CSS.escape(pay)}"]`).checked = true;
  form.note.value = res.noteExtracted;
  form.dispatchEvent(new Event('change'));
  setStatus(root, '✅ Dati estratti con OCR: controllali prima di salvare.', 'ok');
}

// ---------- scheda Spesa ----------

function chips(name, options, selected) {
  return options.map((opt) => `
    <label class="chip"><input type="radio" name="${name}" value="${esc(opt)}" ${opt === selected ? 'checked' : ''}>
    <span>${esc(opt)}</span></label>`).join('');
}

function renderSpesa(root) {
  root.innerHTML = `
    <h2>➕ Registra Nuova Spesa</h2>
    <h3>Step 1: Allegato (Foto o PDF)</h3>
    <div id="spesa-attachment"></div>
    <h3>Step 2: Dati Spesa</h3>
    <form id="spesa-form" class="form" novalidate>
      <label>Data<input name="data" type="date" value="${todayIsoDate()}" required></label>
      <label>Destinazione / Esercente<input name="destinazione" autocomplete="off"></label>
      <label>Scopo<input name="scopo" autocomplete="off"></label>
      <fieldset><legend>Categoria</legend><div class="chips">${chips('categoria', CATEGORY_OPTIONS, RISTORANTI)}</div></fieldset>
      <fieldset id="payment-set"><legend>Metodo pagamento</legend><div class="chips">${chips('pagamento', PAYMENT_OPTIONS, PAYMENT_CC)}</div></fieldset>
      <div class="row">
        <label class="grow">Importo (€)<input name="importo" inputmode="decimal" autocomplete="off" placeholder="0,00"></label>
        <label class="check"><input name="valuta" type="checkbox"> ❌🇪🇺 Valuta estera</label>
      </div>
      <label>Note 📝<input name="note" autocomplete="off"></label>
      <button class="btn primary" type="submit">💾 Salva Spesa</button>
      <div class="status"></div>
    </form>`;
  const form = $('#spesa-form', root);
  const picker = attachmentPicker($('#spesa-attachment', root), {
    onOcr: (file, isPdf) => runOcr(file, isPdf, form, root)
  });
  const updatePayment = () => {
    $('#payment-set', root).hidden = form.categoria.value === TELEPASS;
  };
  form.addEventListener('change', updatePayment);
  updatePayment();
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const data = form.data.value;
    if (!isValidIsoDate(data)) return setStatus(root, '⚠️ Data non valida!', 'error');
    const category = form.categoria.value;
    const payment = form.pagamento.value;
    const importo = parseAmount(form.importo.value) ?? 0;
    if (importo <= 0 && category !== TELEPASS) return setStatus(root, '⚠️ Inserisci un importo valido!', 'error');
    await busy(async () => {
      try {
        const picked = await picker.value();
        let saved = null;
        if (picked) {
          setStatus(root, '⏫ Salvataggio allegato...');
          saved = await storeAttachment(picked, data);
        }
        insertSpesa(state.db, {
          data,
          destinazione: form.destinazione.value,
          scopo: form.scopo.value,
          categoria: categoryKey(category, payment),
          metodo_pagamento: paymentLabel(category, payment),
          importo,
          note: form.note.value,
          allegato_path: saved?.path ?? null,
          valuta_straniera: form.valuta.checked
        });
        await afterLocalChange();
        ['destinazione', 'scopo', 'importo', 'note'].forEach((f) => { form[f].value = ''; });
        form.valuta.checked = false;
        picker.clear();
        setStatus(root, saved && !saved.uploaded
          ? '🎉 Spesa registrata. ⚠️ Dropbox non raggiungibile: l\'allegato è salvato solo su questo dispositivo (caricalo da Settings).'
          : '🎉 Spesa registrata con successo!', 'ok');
      } catch (err) {
        setStatus(root, `❌ Errore durante il salvataggio: ${err.message}`, 'error');
      }
    });
  });
}

// ---------- scheda Registri ----------

function attachmentLabel(path) {
  switch (attachmentKind(path)) {
    case 'dropbox': return '📎 Allegato su Dropbox';
    case 'local': return '📱 Allegato solo su questo dispositivo (da caricare su Dropbox)';
    case 'link': return '🔗 Allegato (link Google Drive)';
    case 'device': return '📱 Allegato solo sul telefono Android';
    default: return '';
  }
}

function renderRegistri(root) {
  const spese = listSpese(state.db);
  root.innerHTML = `
    <h2>📑 Registri / Modifica Spese</h2>
    <p class="muted">${spese.length} spese registrate. Tocca una spesa per modificarla.</p>
    <div class="cards">${spese.map((s) => `
      <button class="card" data-id="${s.id}">
        <div class="card-row"><b>#${s.id} · ${esc(s.data)}</b><b class="amount">€ ${formatAmount(s.importo)}</b></div>
        <div>${esc(s.destinazione ?? '-')}</div>
        <small>Cat: ${esc(s.categoria)} | Pag: ${esc(s.metodo_pagamento ?? 'Standard')}</small>
        ${s.note ? `<small>Note: ${esc(s.note)}</small>` : ''}
        ${s.allegato_path ? `<small class="attach">${attachmentLabel(s.allegato_path)}</small>` : ''}
      </button>`).join('') || '<p>Nessuna spesa registrata.</p>'}
    </div>`;
  root.addEventListener('click', (e) => {
    const card = e.target.closest('[data-id]');
    if (card) editSpesa(Number(card.dataset.id));
  });
}

async function openAttachment(path, container) {
  const kind = attachmentKind(path);
  if (kind === 'link') return window.open(path, '_blank');
  if (kind !== 'dropbox' && kind !== 'local') return;
  const viewer = $('.viewer', container);
  viewer.innerHTML = '<p class="muted">Caricamento allegato...</p>';
  try {
    const bytes = await readAttachment(path);
    if (!bytes) throw new Error('file non trovato');
    const isPdf = isPdfBytes(bytes);
    const url = URL.createObjectURL(new Blob([bytes], { type: isPdf ? 'application/pdf' : 'image/jpeg' }));
    viewer.innerHTML = isPdf
      ? `<a class="btn secondary" href="${url}" target="_blank" rel="noopener">📄 Apri il PDF</a>`
      : `<img src="${url}" alt="Allegato">`;
  } catch (e) {
    viewer.innerHTML = `<p class="status error">❌ Impossibile aprire l'allegato: ${esc(e.message)}</p>`;
  }
}

function editSpesa(id) {
  const s = getSpesa(state.db, id);
  if (!s) return;
  const kind = attachmentKind(s.allegato_path);
  const dialog = openDialog(`
    <h3>Modifica spesa #${s.id}</h3>
    <form class="form" novalidate>
      <label>Data<input name="data" type="date" value="${esc(s.data)}"></label>
      <label>Destinazione<input name="destinazione" value="${esc(s.destinazione)}"></label>
      <label>Scopo<input name="scopo" value="${esc(s.scopo)}"></label>
      <label>Importo (€)<input name="importo" inputmode="decimal" value="${formatAmount(s.importo)}"></label>
      <label>Note<input name="note" value="${esc(s.note)}"></label>
      <small class="muted">Categoria: ${esc(s.categoria)} | Pagamento: ${esc(s.metodo_pagamento ?? '-')}</small>
      ${s.allegato_path ? `<div class="attach">${attachmentLabel(s.allegato_path)}</div>` : ''}
      ${['dropbox', 'local', 'link'].includes(kind) ? '<button type="button" class="btn secondary" data-open>👁️ Apri allegato</button>' : ''}
      <div class="viewer"></div>
      <h4>${s.allegato_path ? 'Sostituisci allegato' : 'Aggiungi allegato'}</h4>
      <div class="edit-attachment"></div>
      <div class="status"></div>
      <div class="dialog-actions">
        <button type="button" class="btn danger" data-delete>🗑️ Elimina</button>
        <button type="button" class="btn secondary" data-close>Annulla</button>
        <button type="submit" class="btn primary">💾 Salva</button>
      </div>
    </form>`);
  const form = $('form', dialog);
  const picker = attachmentPicker($('.edit-attachment', dialog));
  $('[data-open]', dialog)?.addEventListener('click', () => openAttachment(s.allegato_path, dialog));
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    if (!isValidIsoDate(form.data.value)) return setStatus(dialog, '⚠️ Data non valida!', 'error');
    const importo = parseAmount(form.importo.value);
    if (importo == null) return setStatus(dialog, '⚠️ Importo non valido!', 'error');
    await busy(async () => {
      try {
        const picked = await picker.value();
        let saved = null;
        if (picked) {
          setStatus(dialog, '⏫ Salvataggio nuovo allegato...');
          saved = await storeAttachment(picked, form.data.value);
        }
        updateSpesa(state.db, id, {
          data: form.data.value,
          destinazione: form.destinazione.value,
          scopo: form.scopo.value,
          importo,
          note: form.note.value
        });
        if (saved) updateAttachmentPath(state.db, id, saved.path);
        await afterLocalChange();
        // Il vecchio allegato viene eliminato solo dopo aver salvato il nuovo
        if (saved && s.allegato_path) {
          try {
            await deleteAttachment(s.allegato_path);
          } catch {
            // vecchio file non rimosso: nessun dato perso
          }
        }
        dialog.close();
        toast(saved && !saved.uploaded ? '✅ Spesa aggiornata (allegato solo su questo dispositivo).' : '✅ Spesa aggiornata.', 'ok');
        renderTab();
      } catch (err) {
        setStatus(dialog, `❌ Errore durante il salvataggio: ${err.message}`, 'error');
      }
    });
  });
  $('[data-delete]', dialog).addEventListener('click', async () => {
    const extra = kind === 'dropbox' ? "<p>Verrà eliminato anche l'allegato su Dropbox.</p>" : '';
    if (!(await confirmDialog('Eliminare la spesa?', `<p>#${s.id} · ${esc(s.data)} · € ${formatAmount(s.importo)}</p>${extra}`, 'Elimina'))) return;
    deleteSpesa(state.db, id);
    await afterLocalChange();
    dialog.close();
    if (kind === 'dropbox' || kind === 'local') {
      try {
        await deleteAttachment(s.allegato_path);
      } catch {
        toast("⚠️ Spesa eliminata, ma l'allegato su Dropbox non è stato rimosso.", 'error');
      }
    }
    renderTab();
  });
}

// ---------- scheda Report ----------

function renderReport(root) {
  const now = new Date();
  let year = now.getFullYear();
  let month = now.getMonth() + 1;
  const draw = () => {
    const t = monthTotals(speseForMonth(state.db, year, month));
    root.innerHTML = `
      <h2>📊 Riepilogo & Report Spese</h2>
      <div class="row center">
        <button class="btn secondary" data-year="-1">◀</button>
        <b class="grow center-text">Anno ${year}</b>
        <button class="btn secondary" data-year="1">▶</button>
      </div>
      <select class="month">${MONTH_NAMES.map((m, i) => `<option value="${i + 1}" ${i + 1 === month ? 'selected' : ''}>${m}</option>`).join('')}</select>
      <div class="panel">
        <div>Totale Generale Mese:</div>
        <div class="big">€ ${formatAmount(t.total)}</div>
        <hr>
        <div>1) Totale CC (💳 Carta): € ${formatAmount(t.cc)}</div>
        <div>2) Totale Cash (💰 Contanti): € ${formatAmount(t.cash)}</div>
        <div>3) Totale Carta Carburante (💳⛽): € ${formatAmount(t.fuelCard)}</div>
        <div>4) Totale Telepass (🛣️): € ${formatAmount(t.telepass)}</div>
      </div>
      <h3>Breakdown per Categoria</h3>
      ${[...t.byCategory].map(([cat, v]) => `<div class="card-row"><span>${esc(cat)}</span><span>€ ${formatAmount(v.total)} (${v.count})</span></div>`).join('') || '<p class="muted">Nessuna spesa nel mese.</p>'}`;
    root.querySelectorAll('[data-year]').forEach((b) => b.addEventListener('click', () => {
      year += Number(b.dataset.year);
      draw();
    }));
    $('.month', root).addEventListener('change', (e) => {
      month = Number(e.target.value);
      draw();
    });
  };
  draw();
}

// ---------- scheda Export ----------

async function loadExcelTemplate() {
  return (await kvGet('excelTemplate')) ?? null;
}

function monthOptions(selected) {
  return MONTH_NAMES.map((m, i) => `<option value="${i + 1}" ${i + 1 === selected ? 'selected' : ''}>${m}</option>`).join('');
}

async function renderExport(root) {
  const now = new Date();
  const template = await loadExcelTemplate();
  let year = now.getFullYear();
  const current = now.getMonth() + 1;
  root.innerHTML = `
    <h2>📁 Generazione & Esportazione Documenti</h2>
    <div class="row center">
      <button class="btn secondary" data-year="-1">◀</button>
      <b class="grow center-text" data-year-label>Anno ${year}</b>
      <button class="btn secondary" data-year="1">▶</button>
    </div>

    <h3>📊 Genera & Condividi Excel</h3>
    ${template ? `<p class="muted small">Modello: ${esc(template.name)}</p>` : `
      <div class="panel warn">⚠️ Nessun modello Excel caricato: caricalo in <b>Settings › Modello Excel</b>
      (oppure usa il modello standard 2026).</div>`}
    <div class="chips" data-modes>
      <label class="chip"><input type="radio" name="mode" value="SINGLE_MONTH" checked><span>Mese Singolo</span></label>
      <label class="chip"><input type="radio" name="mode" value="MONTH_RANGE"><span>Range Mesi</span></label>
      <label class="chip"><input type="radio" name="mode" value="FULL_YEAR"><span>Anno</span></label>
    </div>
    <label data-start>Mese<select name="start">${monthOptions(current)}</select></label>
    <label data-end hidden>Mese Fine<select name="end">${monthOptions(current)}</select></label>
    <button class="btn primary" data-excel ${template ? '' : 'disabled'}>📊 Genera & Condividi Excel</button>

    <h3>🖼️📄 Genera & Condividi PDF Allegati (scala di grigi)</h3>
    <label>Mese<select name="pdfMonth">${monthOptions(current)}</select></label>
    <button class="btn primary" data-pdf>📄 Genera & Condividi PDF Allegati</button>
    <div class="status"></div>`;

  const modeValue = () => root.querySelector('input[name=mode]:checked').value;
  const updateMode = () => {
    const mode = modeValue();
    root.querySelector('[data-start]').hidden = mode === EXCEL_MODES.FULL_YEAR;
    root.querySelector('[data-end]').hidden = mode !== EXCEL_MODES.MONTH_RANGE;
    root.querySelector('[data-start]').firstChild.textContent = mode === EXCEL_MODES.MONTH_RANGE ? 'Mese Inizio' : 'Mese';
  };
  root.querySelector('[data-modes]').addEventListener('change', updateMode);
  root.querySelectorAll('[data-year]').forEach((b) => b.addEventListener('click', () => {
    year += Number(b.dataset.year);
    root.querySelector('[data-year-label]').textContent = `Anno ${year}`;
  }));

  root.querySelector('[data-excel]').addEventListener('click', () => busy(async () => {
    const mode = modeValue();
    const a = Number(root.querySelector('[name=start]').value);
    const b = Number(root.querySelector('[name=end]').value);
    // Un intervallo scelto "al contrario" non esporterebbe nulla
    const [start, end] = mode === EXCEL_MODES.FULL_YEAR ? [1, 12]
      : mode === EXCEL_MODES.MONTH_RANGE ? [Math.min(a, b), Math.max(a, b)] : [a, a];
    setStatus(root, '📊 Generazione Excel in corso...');
    try {
      
      const bytes = await fillExcelTemplate(template.bytes, {
        startMonth: start,
        endMonth: end,
        speseForMonth: (m) => speseForMonth(state.db, year, m)
      });
      setStatus(root, '✅ Excel generato.', 'ok');
      await shareOrDownload(bytes, excelFileName(mode, year, start, end),
        'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet');
    } catch (e) {
      setStatus(root, `❌ Errore durante la generazione dell'Excel: ${e.message}`, 'error');
    }
  }));

  root.querySelector('[data-pdf]').addEventListener('click', () => busy(async () => {
    const month = Number(root.querySelector('[name=pdfMonth]').value);
    setStatus(root, '📄 Generazione PDF in corso...');
    try {
      const { generatePdfAttachments } = await import('./pdfExport.js');
      const result = await generatePdfAttachments({
        spese: speseForMonth(state.db, year, month),
        year,
        month,
        readAttachment,
        onProgress: (msg) => setStatus(root, msg)
      });
      if (!result) {
        setStatus(root, `⚠️ Nessun allegato trovato per ${MONTH_NAMES[month - 1]} ${year}.`, 'error');
        return;
      }
      setStatus(root, '✅ PDF generato.', 'ok');
      await shareOrDownload(result.bytes, result.fileName, 'application/pdf');
    } catch (e) {
      setStatus(root, `❌ Errore nella generazione del PDF: ${e.message}`, 'error');
    }
  }));
}

// ---------- scheda Settings ----------

async function showTemplateStatus(root) {
  const el = $('[data-template-status]', root);
  const t = await loadExcelTemplate();
  el.innerHTML = t
    ? `<span class="ok-text">✅ Modello Excel attivo: ${esc(t.name)}</span>`
    : '<span class="error-text">⚠️ Nessun modello Excel caricato. Carica \'Note spese 2026.xlsx\'.</span>';
}

async function saveTemplate(bytes, name, root) {
  if (!isXlsx(bytes)) return setStatus(root, '❌ Il file non è un file Excel .xlsx.', 'error');
  try {
    
    const sheets = await templateSheetNames(bytes);
    if (!sheets.includes('Gennaio')) {
      return setStatus(root, '❌ Il file non sembra il modello delle note spese: manca il foglio "Gennaio".', 'error');
    }
  } catch {
    return setStatus(root, '❌ Il file Excel non è leggibile.', 'error');
  }
  await kvSet('excelTemplate', { name, bytes });
  setStatus(root, '✅ Modello Excel salvato con successo!', 'ok');
  showTemplateStatus(root);
}

function renderSettings(root) {
  const localCount = listSpese(state.db).filter((s) => isLocalOnly(s.allegato_path)).length;
  const unsaved = hasUnsavedChanges(state.sync);
  const lastSync = state.sync.lastSyncMs ? formatDateTime(state.sync.lastSyncMs) : 'mai';
  root.innerHTML = `
    <h2>⚙️ Impostazioni</h2>

    <h3>☁️ Dropbox</h3>
    ${state.account ? `
      <div class="panel ok">
        <b>✅ Dropbox collegato</b><div>${esc(state.account)}</div>
        <small>Allegati e backup sono in Dropbox › App › (nome dell'app Dropbox), cartelle 'Allegati' e 'Backups'.</small>
      </div>
      <button class="btn secondary" data-action="disconnect">🚪 Scollega Dropbox</button>
    ` : `
      <div class="panel">
        <b>🔑 Dropbox non collegato</b>
        <small>Collega lo stesso account Dropbox usato sul telefono Android per condividere backup e allegati.</small>
      </div>
      ${BUILD_APP_KEY ? '' : `
        <label>App key Dropbox<input name="appKey" value="${esc(state.appKey)}" autocomplete="off" autocapitalize="off"></label>
        <small class="muted">La stessa App key usata per l'app Android (console Dropbox › Settings).</small>`}
      <button class="btn primary" data-action="login">🔗 Collega Dropbox</button>
      <details>
        <summary>Il collegamento non torna all'app? Usa il codice</summary>
        <p class="muted">Premi il pulsante: Dropbox si apre e, dopo l'autorizzazione, mostra un codice. Copialo e incollalo qui.</p>
        <button class="btn secondary" data-action="login-manual">🔑 Ottieni il codice da Dropbox</button>
        <label>Codice<input name="authCode" autocomplete="off" autocapitalize="off"></label>
        <button class="btn secondary" data-action="login-code">✅ Conferma codice</button>
      </details>
    `}

    <h3>💾 Backup & Ripristino Database</h3>
    <div class="panel ${unsaved ? 'warn' : 'ok'}">
      <b>${unsaved ? '🟠 Modifiche non ancora salvate in un backup' : '🟢 Tutti i dati sono in un backup'}</b>
      <small>Ultimo backup o ripristino su questo dispositivo: ${esc(lastSync)}</small>
      <small>Spese su questo dispositivo: ${countSpese(state.db)}</small>
    </div>
    <label>Nome dispositivo nei backup
      <div class="row"><input class="grow" name="deviceName" value="${esc(state.deviceName)}" autocomplete="off">
      <button class="btn secondary" data-action="save-device">Salva</button></div>
    </label>
    <button class="btn primary" data-action="backup">💾 Esegui Backup Ora su Dropbox</button>
    <button class="btn secondary" data-action="restore">🔄 Ripristina Database da Backup Dropbox</button>
    <p class="muted">Uso consigliato: all'inizio ripristina l'ultimo backup, alla fine esegui un backup.</p>

    ${localCount > 0 ? `
      <p class="muted">📎 ${localCount} allegati sono salvati solo su questo dispositivo e non sono visibili dal telefono Android.</p>
      <button class="btn secondary" data-action="upload-local">📤 Carica su Dropbox gli Allegati Locali (${localCount})</button>` : ''}

    <h3>📊 Modello Excel Note Spese</h3>
    <div data-template-status class="muted">...</div>
    <button class="btn secondary" data-action="template">📁 Carica Modello Excel (.xlsx)</button>
    <input type="file" name="templateFile" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" hidden>
    <button class="btn secondary" data-action="template-default">📄 Usa il modello standard 2026</button>

    <h3>📥 File del database</h3>
    <button class="btn secondary" data-action="import">📥 Importa File Database (.db)</button>
    <input type="file" name="importFile" accept=".db,.sqlite,application/octet-stream,application/x-sqlite3" hidden>
    <button class="btn secondary" data-action="export">📤 Salva una copia del database (.db)</button>

    <div class="status"></div>
    <p class="muted small">Versione ${esc(APP_VERSION)} · indirizzo di ritorno Dropbox: <code>${esc(dropbox.redirectUri())}</code></p>`;

  root.addEventListener('click', async (e) => {
    const action = e.target.closest('[data-action]')?.dataset.action;
    if (!action) return;
    switch (action) {
      case 'login':
      case 'login-manual': {
        const key = BUILD_APP_KEY || $('[name=appKey]', root)?.value.trim();
        if (!key) return setStatus(root, "⚠️ Inserisci l'App key Dropbox.", 'error');
        if (!BUILD_APP_KEY) await kvSet('appKey', key);
        state.appKey = key;
        await dropbox.startLogin(key, { manual: action === 'login-manual' });
        break;
      }
      case 'login-code': {
        const code = $('[name=authCode]', root).value.trim();
        if (!code) return setStatus(root, '⚠️ Incolla il codice mostrato da Dropbox.', 'error');
        await busy(async () => {
          try {
            state.account = await dropbox.completeLogin(code);
            toast(`🎉 Dropbox collegato: ${state.account}`, 'ok');
            renderTab();
            checkNewerBackup();
          } catch (err) {
            setStatus(root, `❌ ${err.message}`, 'error');
          }
        });
        break;
      }
      case 'disconnect':
        await busy(async () => {
          await dropbox.disconnect();
          state.account = '';
          state.newerBackup = null;
          renderBanners();
          renderTab();
          toast('Dropbox scollegato.');
        });
        break;
      case 'save-device': {
        state.deviceName = sanitizeDeviceName($('[name=deviceName]', root).value);
        await kvSet('deviceName', state.deviceName);
        $('[name=deviceName]', root).value = state.deviceName;
        setStatus(root, `✅ Nome dispositivo: ${state.deviceName}`, 'ok');
        break;
      }
      case 'backup':
        await doBackup();
        break;
      case 'restore':
        await showRestoreList();
        break;
      case 'import':
        if (await confirmDialog('Importare un database?', '<p>⚠️ Tutte le spese di questo dispositivo verranno sostituite con quelle del file selezionato.</p>', 'Scegli file')) {
          $('[name=importFile]', root).click();
        }
        break;
      case 'upload-local': {
        if (!state.account) return setStatus(root, '⚠️ Collega prima Dropbox per caricare gli allegati.', 'error');
        await busy(async () => {
          toast('Caricamento allegati locali su Dropbox...');
          const r = await uploadLocalAttachments(listSpese(state.db), async (id, path) => {
            updateAttachmentPath(state.db, id, path);
            await afterLocalChange();
          });
          renderTab();
          toast(r.failed === 0
            ? `🎉 ${r.uploaded} allegati caricati su Dropbox. Esegui un backup per renderli visibili sul telefono Android.`
            : `⚠️ ${r.uploaded} allegati caricati, ${r.failed} non caricati: riprova più tardi.`, r.failed ? 'error' : 'ok');
        });
        break;
      }
      case 'template':
        $('[name=templateFile]', root).click();
        break;
      case 'template-default':
        await busy(async () => {
          try {
            const res = await fetch(`${import.meta.env.BASE_URL}modello/Note%20spese%202026.xlsx`);
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            await saveTemplate(new Uint8Array(await res.arrayBuffer()), 'Note spese 2026.xlsx (standard)', root);
          } catch (e) {
            setStatus(root, `❌ Impossibile scaricare il modello standard: ${e.message}`, 'error');
          }
        });
        break;
      case 'export': {
        const blob = new Blob([exportDatabase(state.db)], { type: 'application/x-sqlite3' });
        const name = `expense_report_${todayIsoDate()}_${state.deviceName}.db`;
        const file = new File([blob], name, { type: 'application/x-sqlite3' });
        if (navigator.canShare?.({ files: [file] })) {
          try {
            await navigator.share({ files: [file], title: name });
          } catch {
            // condivisione annullata
          }
        } else {
          const a = Object.assign(document.createElement('a'), { href: URL.createObjectURL(blob), download: name });
          a.click();
        }
        break;
      }
    }
  });

  $('[name=templateFile]', root).addEventListener('change', async (e) => {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (file) await saveTemplate(new Uint8Array(await file.arrayBuffer()), file.name, root);
  });
  showTemplateStatus(root);

  $('[name=importFile]', root).addEventListener('change', async (e) => {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;
    try {
      const imported = openDatabase(state.SQL, new Uint8Array(await file.arrayBuffer()), { requireExpenses: true });
      state.db.close();
      state.db = imported;
      await afterLocalChange();
      toast(`🎉 Importate ${countSpese(state.db)} spese.`, 'ok');
      renderTab();
    } catch (err) {
      setStatus(root, `❌ Impossibile importare il file. ${err.message}`, 'error');
    }
  });
}

// ---------- navigazione ----------

const TABS = {
  spesa: renderSpesa,
  registri: renderRegistri,
  report: renderReport,
  export: renderExport,
  settings: renderSettings
};

function renderTab() {
  // Un nuovo contenitore elimina anche i listener della scheda precedente
  const old = $('#content');
  const fresh = old.cloneNode(false);
  old.replaceWith(fresh);
  TABS[state.tab](fresh);
  document.querySelectorAll('nav [data-tab]').forEach((b) => b.classList.toggle('active', b.dataset.tab === state.tab));
}

/** Gestisce il ritorno da Dropbox (?code=...) dopo l'autorizzazione. */
async function handleOAuthRedirect() {
  const params = new URLSearchParams(location.search);
  const code = params.get('code');
  const error = params.get('error');
  if (!code && !error) return;
  history.replaceState(null, '', location.pathname);
  if (error) {
    toast(`⚠️ Collegamento a Dropbox annullato (${params.get('error_description') ?? error}).`, 'error');
    return;
  }
  if (!dropbox.hasPendingLogin()) {
    // L'autorizzazione è tornata in Safari invece che nell'app installata:
    // il codice va incollato nell'app (Settings › "Il collegamento non torna all'app?")
    openDialog(`
      <h3>Quasi fatto!</h3>
      <p>Copia questo codice e incollalo nell'app installata, in Settings › "Il collegamento non torna all'app? Usa il codice":</p>
      <p class="code">${esc(code)}</p>
      <div class="dialog-actions"><button class="btn primary" data-close>OK</button></div>`);
    return;
  }
  await busy(async () => {
    try {
      state.account = await dropbox.completeLogin(code);
      state.tab = 'settings';
      toast(`🎉 Dropbox collegato: ${state.account}`, 'ok');
    } catch (e) {
      toast(`❌ ${e.message}`, 'error');
    }
  });
}

async function start() {
  try {
    await loadLocalState();
  } catch (e) {
    $('#content').innerHTML = `<p class="status error">❌ Impossibile avviare l'app: ${esc(e.message)}</p>`;
    return;
  }
  requestPersistentStorage();
  document.querySelectorAll('nav [data-tab]').forEach((b) => b.addEventListener('click', () => {
    state.tab = b.dataset.tab;
    renderTab();
  }));
  await handleOAuthRedirect();
  renderTab();
  renderBanners();
  checkNewerBackup();
  // Quando l'app torna in primo piano controlla se nel frattempo è arrivato un backup più recente
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') checkNewerBackup();
  });
}

start();
