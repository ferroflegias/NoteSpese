// Lettura dei PDF con pdf.js (caricato solo quando serve).
import workerUrl from 'pdfjs-dist/legacy/build/pdf.worker.min.mjs?url';

let pdfjsPromise;

function pdfjs() {
  pdfjsPromise ??= import('pdfjs-dist/legacy/build/pdf.min.mjs').then((lib) => {
    lib.GlobalWorkerOptions.workerSrc = workerUrl;
    return lib;
  });
  return pdfjsPromise;
}

const assetsBase = () => new URL(`${import.meta.env.BASE_URL}pdfjs/`, location.origin).href;

/** Apre un PDF; chiamare closePdf() quando non serve più. */
export async function openPdf(bytes) {
  const lib = await pdfjs();
  return lib.getDocument({
    data: bytes.slice(),
    cMapUrl: `${assetsBase()}cmaps/`,
    cMapPacked: true,
    standardFontDataUrl: `${assetsBase()}standard_fonts/`,
    wasmUrl: `${assetsBase()}wasm/`,
    isEvalSupported: false
  }).promise;
}

/**
 * Disegna la pagina [pageNumber] (da 1) su una canvas con sfondo bianco, larga circa
 * [targetWidth] px (i PDF hanno spesso sfondo trasparente: senza bianco l'OCR non legge nulla).
 */
export async function renderPdfPage(pdf, pageNumber, targetWidth = 1600) {
  const page = await pdf.getPage(pageNumber);
  const base = page.getViewport({ scale: 1 });
  const scale = Math.min(4, targetWidth / base.width);
  const viewport = page.getViewport({ scale });
  const canvas = document.createElement('canvas');
  canvas.width = Math.ceil(viewport.width);
  canvas.height = Math.ceil(viewport.height);
  const ctx = canvas.getContext('2d');
  ctx.fillStyle = '#ffffff';
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  await page.render({ canvasContext: ctx, canvas, viewport, background: 'rgb(255,255,255)' }).promise;
  page.cleanup();
  return canvas;
}

/** Libera la memoria del documento (in pdf.js 6 si chiude tramite il suo loadingTask). */
export function closePdf(pdf) {
  try {
    (pdf?.loadingTask ?? pdf)?.destroy?.();
  } catch {
    // già chiuso
  }
}
