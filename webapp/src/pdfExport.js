// PDF mensile degli allegati in scala di grigi: stessa impaginazione dell'app Android
// (util/DocumentGenerator.kt, generatePdfAttachments): 4 allegati per pagina, i PDF uniti in coda.
import { PDFDocument, StandardFonts, rgb } from 'pdf-lib';
import { formatAmount, MONTH_NAMES } from './core/formats.js';
import { isPdfBytes, loadImage, drawToCanvas, toGrayscale, canvasToJpeg, releaseCanvas } from './imageTools.js';
import { openPdf, renderPdfPage, closePdf } from './pdfTools.js';

const PAGE_W = 595;
const PAGE_H = 842;
const COLS = [30, 305];
const ROWS = [70, 440];
const CELL_W = 260;
const CELL_H = 290;
const BLACK = rgb(0, 0, 0);

/** I caratteri non disponibili nei font standard del PDF (es. emoji) diventano "?". */
function safeText(font, text) {
  let out = '';
  for (const ch of String(text)) {
    try {
      font.encodeText(ch);
      out += ch;
    } catch {
      out += '?';
    }
  }
  return out;
}

async function embedGrayscale(doc, canvas) {
  toGrayscale(canvas);
  const jpeg = await canvasToJpeg(canvas, 0.85);
  releaseCanvas(canvas);
  return doc.embedJpg(new Uint8Array(await jpeg.arrayBuffer()));
}

function drawFitted(page, image, left, top, maxW, maxH) {
  const ratio = Math.min(maxW / image.width, maxH / image.height);
  const w = image.width * ratio;
  const h = image.height * ratio;
  page.drawImage(image, { x: left, y: PAGE_H - top - h, width: w, height: h });
}

/**
 * [spese]: spese del mese con allegato; [readAttachment](path) restituisce i byte dell'allegato
 * (o null se non disponibile); [onProgress] riceve messaggi di avanzamento.
 * Restituisce null se nel mese non ci sono allegati.
 */
export async function generatePdfAttachments({ spese, year, month, readAttachment, onProgress }) {
  const items = spese.filter((s) => s.allegato_path);
  if (items.length === 0) return null;

  const doc = await PDFDocument.create();
  const font = await doc.embedFont(StandardFonts.Helvetica);
  const bold = await doc.embedFont(StandardFonts.HelveticaBold);
  const text = (page, str, x, y, size, f = font) =>
    page.drawText(safeText(f, str), { x, y: PAGE_H - y, size, font: f, color: BLACK });

  let page = doc.addPage([PAGE_W, PAGE_H]);
  text(page, `Allegati Spese (${items.length} Voci) - ${MONTH_NAMES[month - 1]} ${year}`, 30, 40, 16, bold);

  const pendingPdfs = [];
  for (const [index, spesa] of items.entries()) {
    onProgress?.(`📄 Allegato ${index + 1} di ${items.length}...`);
    const slot = index % 4;
    if (index > 0 && slot === 0) page = doc.addPage([PAGE_W, PAGE_H]);
    const x = COLS[slot % 2];
    const y = ROWS[Math.floor(slot / 2)];

    text(page, `ID #${spesa.id ?? '-'} | Data: ${spesa.data}`, x, y + 12, 10, bold);
    text(page, `Importo: € ${formatAmount(spesa.importo)}`, x, y + 26, 10, bold);
    text(page, `Destinazione: ${spesa.destinazione ?? '-'}`.slice(0, 60), x, y + 40, 9);
    text(page, `Cat: ${spesa.categoria} | Pag: ${spesa.metodo_pagamento ?? 'Standard'}`, x, y + 54, 9);

    let bytes = null;
    try {
      bytes = await readAttachment(spesa.allegato_path);
    } catch {
      bytes = null;
    }
    if (!bytes) {
      text(page, '[Allegato non disponibile]', x, y + 80, 9);
      continue;
    }
    if (isPdfBytes(bytes)) {
      pendingPdfs.push({ spesa, bytes });
      text(page, '[Documento PDF allegato e unito in coda]', x, y + 80, 10, bold);
      continue;
    }
    try {
      const img = await loadImage(new Blob([bytes]));
      // ~6 volte la dimensione del riquadro: nitido anche stampato
      const canvas = drawToCanvas(img, { maxDimension: Math.max(CELL_W, CELL_H) * 6 });
      img.close?.();
      drawFitted(page, await embedGrayscale(doc, canvas), x, y + 65, CELL_W, CELL_H);
    } catch {
      text(page, '[Immagine non visualizzabile]', x, y + 80, 9);
    }
  }

  for (const { spesa, bytes } of pendingPdfs) {
    let pdf;
    try {
      pdf = await openPdf(bytes);
      for (let i = 1; i <= pdf.numPages; i++) {
        onProgress?.(`📄 PDF della spesa #${spesa.id}: pagina ${i} di ${pdf.numPages}...`);
        const p = doc.addPage([PAGE_W, PAGE_H]);
        text(p, `Allegato PDF in Coda - ID #${spesa.id ?? '-'} (Pagina ${i}/${pdf.numPages})`, 30, 30, 10, bold);
        const canvas = await renderPdfPage(pdf, i, 1800);
        drawFitted(p, await embedGrayscale(doc, canvas), 30, 50, PAGE_W - 60, PAGE_H - 80);
      }
    } catch {
      const p = doc.addPage([PAGE_W, PAGE_H]);
      text(p, `Allegato PDF della spesa #${spesa.id ?? '-'} non leggibile`, 30, 30, 10, bold);
    } finally {
      closePdf(pdf);
    }
  }

  return {
    bytes: await doc.save(),
    fileName: `Allegati_Spese_${MONTH_NAMES[month - 1]}_${year}.pdf`
  };
}
