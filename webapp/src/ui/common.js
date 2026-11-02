// Utilità dell'interfaccia condivise dalle schede.

export const $ = (sel, root = document) => root.querySelector(sel);

export function esc(value) {
  return String(value ?? '').replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  })[c]);
}

export function toast(message, kind = 'info') {
  const el = $('#toast');
  el.textContent = message;
  el.className = `toast show ${kind}`;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => el.classList.remove('show'), kind === 'error' ? 8000 : 4500);
}

export function setStatus(container, message, kind = 'info') {
  const el = $('.status', container);
  if (!el) return toast(message, kind);
  el.textContent = message;
  el.className = `status ${kind}`;
}

/** Finestra di dialogo modale; restituisce l'elemento <dialog>. */
export function openDialog(html, { onClose } = {}) {
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

export function confirmDialog(title, bodyHtml, confirmLabel = 'Conferma') {
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

/**
 * Apre il pannello di condivisione di iOS (Mail, WhatsApp, Salva su File...) o scarica il file.
 * Safari consente la condivisione solo subito dopo un tocco: se la generazione del file ha
 * richiesto troppo tempo, compare un pulsante "Condividi" da toccare.
 */
export async function shareOrDownload(bytes, fileName, mimeType) {
  const file = new File([bytes], fileName, { type: mimeType });
  if (navigator.canShare?.({ files: [file] })) {
    try {
      await navigator.share({ files: [file], title: fileName });
      return;
    } catch (e) {
      if (e?.name === 'AbortError') return; // condivisione annullata dall'utente
      if (e?.name === 'NotAllowedError') return shareButtonDialog(file);
    }
  }
  download(file);
}

function download(file) {
  const url = URL.createObjectURL(file);
  const a = Object.assign(document.createElement('a'), { href: url, download: file.name });
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

function shareButtonDialog(file) {
  const dialog = openDialog(`
    <h3>✅ File pronto</h3>
    <p>${esc(file.name)}</p>
    <button class="btn primary" data-share>📤 Condividi</button>
    <button class="btn secondary" data-download>⬇️ Scarica</button>
    <button class="btn secondary" data-close>Chiudi</button>`);
  dialog.querySelector('[data-share]').addEventListener('click', async () => {
    try {
      await navigator.share({ files: [file], title: file.name });
      dialog.close();
    } catch (e) {
      if (e?.name !== 'AbortError') toast(`⚠️ Condivisione non riuscita: ${e.message}`, 'error');
    }
  });
  dialog.querySelector('[data-download]').addEventListener('click', () => {
    download(file);
    dialog.close();
  });
}
