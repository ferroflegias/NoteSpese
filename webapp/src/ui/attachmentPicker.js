// Scelta dell'allegato (foto con ritaglio, oppure PDF), con anteprima e OCR.
import { esc, toast } from './common.js';
import { openCropDialog } from './cropDialog.js';
import { isPdfBytes } from '../imageTools.js';

/**
 * Inserisce in [container] i pulsanti Foto/PDF. [onOcr](file, isPdf) viene chiamata dal
 * pulsante "Estrai testo". Restituisce { value(), clear() }: value() dà { bytes, isPdf } o null.
 */
export function attachmentPicker(container, { onOcr } = {}) {
  let source = null; // foto originale (per ritagliarla di nuovo)
  let photo = null; // JPEG pronto da salvare
  let pdf = null; // file PDF
  let previewUrl = null;

  container.innerHTML = `
    <div class="row">
      <button type="button" class="btn secondary grow" data-photo>Foto 📷🖼️</button>
      <button type="button" class="btn secondary grow" data-pdf>PDF 📄</button>
    </div>
    <input type="file" accept="image/*" hidden data-photo-input>
    <input type="file" accept="application/pdf,.pdf" hidden data-pdf-input>
    <div class="attach-preview"></div>`;
  const preview = container.querySelector('.attach-preview');
  const photoInput = container.querySelector('[data-photo-input]');
  const pdfInput = container.querySelector('[data-pdf-input]');

  function render() {
    if (previewUrl) URL.revokeObjectURL(previewUrl);
    previewUrl = null;
    if (photo) {
      previewUrl = URL.createObjectURL(photo);
      preview.innerHTML = `
        <img class="thumb" src="${previewUrl}" alt="Anteprima">
        <div class="row">
          <button type="button" class="btn secondary grow" data-recrop>✂️ Ritaglia</button>
          <button type="button" class="btn secondary grow" data-remove>🗑️ Rimuovi</button>
        </div>
        ${onOcr ? '<button type="button" class="btn primary" data-ocr>🔍 Estrai Testo con OCR</button>' : ''}`;
    } else if (pdf) {
      preview.innerHTML = `
        <div class="panel">📄 ${esc(pdf.name)} (${Math.max(1, Math.round(pdf.size / 1024))} KB)</div>
        <button type="button" class="btn secondary" data-remove>🗑️ Rimuovi</button>
        ${onOcr ? '<button type="button" class="btn primary" data-ocr>🔍 Estrai Testo con OCR</button>' : ''}`;
    } else {
      preview.innerHTML = '';
    }
  }

  async function cropFrom(file) {
    const cropped = await openCropDialog(file);
    if (!cropped) return;
    source = file;
    photo = cropped;
    pdf = null;
    render();
  }

  container.addEventListener('click', async (e) => {
    const t = e.target.closest('button');
    if (!t) return;
    if (t.matches('[data-photo]')) photoInput.click();
    else if (t.matches('[data-pdf]')) pdfInput.click();
    else if (t.matches('[data-recrop]') && source) await cropFrom(source);
    else if (t.matches('[data-remove]')) clear();
    else if (t.matches('[data-ocr]')) {
      t.disabled = true;
      try {
        await onOcr(photo ?? pdf, Boolean(pdf));
      } finally {
        t.disabled = false;
      }
    }
  });
  photoInput.addEventListener('change', async () => {
    const file = photoInput.files?.[0];
    photoInput.value = '';
    if (file) await cropFrom(file);
  });
  pdfInput.addEventListener('change', async () => {
    const file = pdfInput.files?.[0];
    pdfInput.value = '';
    if (!file) return;
    if (!isPdfBytes(new Uint8Array(await file.slice(0, 4).arrayBuffer()))) {
      toast('⚠️ Il file scelto non è un PDF.', 'error');
      return;
    }
    pdf = file;
    photo = null;
    source = null;
    render();
  });

  function clear() {
    source = photo = pdf = null;
    render();
  }

  return {
    async value() {
      const file = photo ?? pdf;
      if (!file) return null;
      return { bytes: new Uint8Array(await file.arrayBuffer()), isPdf: Boolean(pdf) };
    },
    clear
  };
}
