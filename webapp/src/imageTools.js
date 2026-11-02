// Immagini e PDF nel browser: ridimensionamento, ritaglio, scala di grigi, rendering delle pagine PDF.

/** Lato più lungo degli allegati salvati (come ImageUtils.ATTACHMENT_MAX_DIMENSION su Android). */
export const ATTACHMENT_MAX_DIMENSION = 3200;
/** Lato più lungo per anteprima, ritaglio e OCR. */
export const PREVIEW_MAX_DIMENSION = 2048;
export const JPEG_QUALITY = 0.92;

const PDF_MAGIC = [0x25, 0x50, 0x44, 0x46]; // %PDF

export function isPdfBytes(bytes) {
  return bytes?.length >= 4 && PDF_MAGIC.every((b, i) => bytes[i] === b);
}

/** Decodifica un'immagine applicando la rotazione EXIF delle foto. */
export async function loadImage(blob) {
  if ('createImageBitmap' in window) {
    try {
      return await createImageBitmap(blob, { imageOrientation: 'from-image' });
    } catch {
      // formato non supportato da createImageBitmap: si prova con <img>
    }
  }
  const url = URL.createObjectURL(blob);
  try {
    const img = new Image();
    img.decoding = 'async';
    img.src = url;
    await img.decode();
    return img;
  } finally {
    URL.revokeObjectURL(url);
  }
}

const widthOf = (src) => src.naturalWidth ?? src.width;
const heightOf = (src) => src.naturalHeight ?? src.height;

/**
 * Disegna [source] (o la sua parte [crop] in pixel) su una nuova canvas, ruotata di [rotation]
 * gradi (0/90/180/270) e con il lato più lungo al massimo [maxDimension].
 */
export function drawToCanvas(source, { crop = null, rotation = 0, maxDimension = ATTACHMENT_MAX_DIMENSION } = {}) {
  const sx = crop?.x ?? 0;
  const sy = crop?.y ?? 0;
  const sw = crop?.width ?? widthOf(source);
  const sh = crop?.height ?? heightOf(source);
  const scale = Math.min(1, maxDimension / Math.max(sw, sh));
  const w = Math.max(1, Math.round(sw * scale));
  const h = Math.max(1, Math.round(sh * scale));
  const rotated = rotation % 180 !== 0;
  const canvas = document.createElement('canvas');
  canvas.width = rotated ? h : w;
  canvas.height = rotated ? w : h;
  const ctx = canvas.getContext('2d');
  ctx.fillStyle = '#ffffff';
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  ctx.translate(canvas.width / 2, canvas.height / 2);
  ctx.rotate((rotation * Math.PI) / 180);
  ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(source, sx, sy, sw, sh, -w / 2, -h / 2, w, h);
  return canvas;
}

/** Converte la canvas in scala di grigi (per il PDF da stampare). */
export function toGrayscale(canvas) {
  const ctx = canvas.getContext('2d');
  const img = ctx.getImageData(0, 0, canvas.width, canvas.height);
  const d = img.data;
  for (let i = 0; i < d.length; i += 4) {
    const y = 0.299 * d[i] + 0.587 * d[i + 1] + 0.114 * d[i + 2];
    d[i] = d[i + 1] = d[i + 2] = y;
  }
  ctx.putImageData(img, 0, 0);
  return canvas;
}

export function canvasToJpeg(canvas, quality = JPEG_QUALITY) {
  return new Promise((resolve, reject) => {
    canvas.toBlob((blob) => (blob ? resolve(blob) : reject(new Error('conversione JPEG non riuscita'))), 'image/jpeg', quality);
  });
}

/** Libera la memoria della canvas (importante su iPhone, che ha limiti stretti). */
export function releaseCanvas(canvas) {
  canvas.width = 0;
  canvas.height = 0;
}

/** Foto pronta da salvare come allegato: JPEG con il lato più lungo al massimo 3200 px. */
export async function prepareAttachmentJpeg(source, options = {}) {
  const canvas = drawToCanvas(source, { maxDimension: ATTACHMENT_MAX_DIMENSION, ...options });
  try {
    return await canvasToJpeg(canvas);
  } finally {
    releaseCanvas(canvas);
  }
}
