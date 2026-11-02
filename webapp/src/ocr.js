// OCR sul dispositivo con Tesseract.js: nessun dato esce dall'iPhone. Motore e dati della lingua
// italiana (~5 MB) vengono scaricati al primo uso e poi restano in cache (funziona anche offline).
import { parseReceiptText } from './core/ocrParser.js';
import { loadImage, drawToCanvas, releaseCanvas, PREVIEW_MAX_DIMENSION } from './imageTools.js';
import { openPdf, renderPdfPage, closePdf } from './pdfTools.js';

let workerPromise;
let progressListener = null;

function getWorker() {
  workerPromise ??= (async () => {
    const { createWorker } = await import('tesseract.js');
    const base = new URL(`${import.meta.env.BASE_URL}ocr/`, location.origin).href;
    return createWorker('ita', 1, {
      workerPath: `${base}worker.min.js`,
      corePath: base,
      langPath: base,
      workerBlobURL: false,
      logger: (m) => progressListener?.(m)
    });
  })().catch((e) => {
    workerPromise = null;
    throw e;
  });
  return workerPromise;
}

/**
 * Testo dello scontrino ([file]: foto o PDF, di cui si legge la prima pagina) e dati estratti.
 * [onProgress] riceve messaggi di avanzamento per l'utente.
 */
export async function analyzeReceipt(file, { isPdf, onProgress } = {}) {
  let canvas;
  if (isPdf) {
    const pdf = await openPdf(new Uint8Array(await file.arrayBuffer()));
    try {
      canvas = await renderPdfPage(pdf, 1, 2000);
    } finally {
      closePdf(pdf);
    }
  } else {
    const img = await loadImage(file);
    canvas = drawToCanvas(img, { maxDimension: PREVIEW_MAX_DIMENSION });
    img.close?.();
  }
  progressListener = (m) => {
    if (m.status === 'recognizing text') onProgress?.(`🔍 Lettura del testo... ${Math.round(m.progress * 100)}%`);
    else if (m.status?.includes('loading')) onProgress?.('⏬ Preparazione OCR (solo la prima volta può richiedere qualche secondo)...');
  };
  try {
    const worker = await getWorker();
    const { data } = await worker.recognize(canvas);
    return parseReceiptText(data.text);
  } finally {
    progressListener = null;
    releaseCanvas(canvas);
  }
}
