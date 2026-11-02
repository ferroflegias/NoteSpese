import initSqlJs from 'sql.js';
import wasmUrl from 'sql.js/dist/sql-wasm.wasm?url';
import './style.css';

import { kvGet, kvSet, requestPersistentStorage } from './storage.js';
import * as dropbox from './dropbox.js';
import { listBackups, uploadBackup, downloadBackup } from './backupService.js';
import {
  createDatabase, openDatabase, countSpese, listSpese, speseForMonth, getSpesa,
  insertSpesa, updateSpesa, deleteSpesa, exportDatabase, DbError
} from './core/dbcore.js';
import {
  CATEGORY_OPTIONS, PAYMENT_OPTIONS, RISTORANTI, PAYMENT_CC, TELEPASS,
  categoryKey, paymentLabel, monthTotals
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

const $ = (sel, root = document) => root.querySelector(sel);

function esc(value) {
  return String(value ?? '').replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  })[c]);
}

function toast(message, kind = 'info') {
  const el = $('#toast');
  el.textContent = message;
  el.className = `toast show ${kind}`;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => el.classList.remove('show'), kind === 'error' ? 8000 : 4500);
}

function setStatus(container, message, kind = 'info') {
  const el = $('.status', container);
  if (!el) return toast(message, kind);
  el.textContent = message;
  el.className = `status ${kind}`;
}

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

/** Finestra di dialogo modale; restituisce l'elemento <dialog>. */
function openDialog(html, { onClose } = {}) {
  const dialog = document.createElement('dialog');
  dialog.innerHTML = `<div class="dialog-body">${html}</div>`;
  document.body.appendChild(dialog);
  dialog.addEventListener('close', () => {
    onClose?.();
    dialog.remove();
  });
  dialog.addEventListener('click', (e) => {
    if (e.target.matches('[data-close]')) dialog.close();
  });
  dialog.showModal();
  return dialog;
}

function confirmDialog(title, bodyHtml, confirmLabel = 'Conferma') {
  return new Promise((resolve) => {
    let result = false;
    const dialog = openDialog(`
      <h3>${title}</h3>
      <div>${bodyHtml}</div>
      <div class="dialog-actions">
        <button class="btn secondary" data-close>Annulla</button>
        <button class="btn danger" data-ok>${esc(confirmLabel)}</button>
      </div>`, { onClose: () => resolve(result) });
    $('[data-ok]', dialog).addEventListener('click', () => {
      result = true;
      dialog.close();
    });
  });
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

// ---------- scheda Spesa ----------

function chips(name, options, selected) {
  return options.map((opt) => `
    <label class="chip"><input type="radio" name="${name}" value="${esc(opt)}" ${opt === selected ? 'checked' : ''}>
    <span>${esc(opt)}</span></label>`).join('');
}

function renderSpesa(root) {
  root.innerHTML = `
    <h2>➕ Registra Nuova Spesa</h2>
    <p class="muted">📷 Foto, PDF e lettura automatica dello scontrino arriveranno nella prossima versione:
    per ora le spese si inseriscono a mano.</p>
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
    try {
      insertSpesa(state.db, {
        data,
        destinazione: form.destinazione.value,
        scopo: form.scopo.value,
        categoria: categoryKey(category, payment),
        metodo_pagamento: paymentLabel(category, payment),
        importo,
        note: form.note.value,
        valuta_straniera: form.valuta.checked
      });
      await afterLocalChange();
      ['destinazione', 'scopo', 'importo', 'note'].forEach((f) => { form[f].value = ''; });
      form.valuta.checked = false;
      setStatus(root, '🎉 Spesa registrata con successo!', 'ok');
    } catch (err) {
      setStatus(root, `❌ Errore durante il salvataggio: ${err.message}`, 'error');
    }
  });
}

// ---------- scheda Registri ----------

function attachmentLabel(path) {
  switch (attachmentKind(path)) {
    case 'dropbox': return '📎 Allegato su Dropbox';
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
  if (kind !== 'dropbox') return;
  const viewer = $('.viewer', container);
  viewer.innerHTML = '<p class="muted">Scaricamento allegato...</p>';
  try {
    const bytes = await dropbox.downloadFile(path.slice(dropbox.PATH_PREFIX.length));
    const isPdf = path.toLowerCase().endsWith('.pdf');
    const url = URL.createObjectURL(new Blob([bytes], { type: isPdf ? 'application/pdf' : 'image/jpeg' }));
    viewer.innerHTML = isPdf
      ? `<a class="btn secondary" href="${url}" target="_blank" rel="noopener">📄 Apri il PDF</a>`
      : `<img src="${url}" alt="Allegato">`;
  } catch (e) {
    viewer.innerHTML = `<p class="status error">❌ Impossibile scaricare l'allegato: ${esc(e.message)}</p>`;
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
      ${kind === 'dropbox' || kind === 'link' ? '<button type="button" class="btn secondary" data-open>👁️ Apri allegato</button>' : ''}
      <div class="viewer"></div>
      <div class="status"></div>
      <div class="dialog-actions">
        <button type="button" class="btn danger" data-delete>🗑️ Elimina</button>
        <button type="button" class="btn secondary" data-close>Annulla</button>
        <button type="submit" class="btn primary">💾 Salva</button>
      </div>
    </form>`);
  const form = $('form', dialog);
  $('[data-open]', dialog)?.addEventListener('click', () => openAttachment(s.allegato_path, dialog));
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    if (!isValidIsoDate(form.data.value)) return setStatus(dialog, '⚠️ Data non valida!', 'error');
    const importo = parseAmount(form.importo.value);
    if (importo == null) return setStatus(dialog, '⚠️ Importo non valido!', 'error');
    updateSpesa(state.db, id, {
      data: form.data.value,
      destinazione: form.destinazione.value,
      scopo: form.scopo.value,
      importo,
      note: form.note.value
    });
    await afterLocalChange();
    dialog.close();
    toast('✅ Spesa aggiornata.', 'ok');
    renderTab();
  });
  $('[data-delete]', dialog).addEventListener('click', async () => {
    const extra = kind === 'dropbox' ? "<p>Verrà eliminato anche l'allegato su Dropbox.</p>" : '';
    if (!(await confirmDialog('Eliminare la spesa?', `<p>#${s.id} · ${esc(s.data)} · € ${formatAmount(s.importo)}</p>${extra}`, 'Elimina'))) return;
    deleteSpesa(state.db, id);
    await afterLocalChange();
    dialog.close();
    if (kind === 'dropbox' && state.account) {
      try {
        await dropbox.deleteFile(s.allegato_path.slice(dropbox.PATH_PREFIX.length));
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

function renderExport(root) {
  root.innerHTML = `
    <h2>📁 Export Excel & PDF</h2>
    <div class="panel">
      <p>La compilazione del modello Excel e il PDF degli allegati in scala di grigi arriveranno
      nella prossima versione della WebApp.</p>
      <p>Nel frattempo puoi generarli dall'app Android: fai un backup qui, ripristinalo sul
      telefono Android e usa la scheda Export.</p>
    </div>`;
}

// ---------- scheda Settings ----------

function renderSettings(root) {
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
