// Ritaglio della foto dello scontrino (come CropImageDialog dell'app Android), con rotazione.
import { openDialog, toast } from './common.js';
import { loadImage, drawToCanvas, canvasToJpeg, releaseCanvas, ATTACHMENT_MAX_DIMENSION } from '../imageTools.js';

const DISPLAY_MAX = 1200;
const MIN_BOX = 40;

/**
 * Apre il ritaglio di [file]. Restituisce il JPEG da salvare (ritagliato o intero),
 * oppure null se l'utente annulla.
 */
export async function openCropDialog(file) {
  let img;
  try {
    img = await loadImage(file);
  } catch {
    toast('⚠️ Impossibile aprire la foto per il ritaglio: verrà usata così com\'è.', 'error');
    return file;
  }
  // Foto di lavoro (con la rotazione applicata) alla risoluzione degli allegati
  let work = drawToCanvas(img, { maxDimension: ATTACHMENT_MAX_DIMENSION });
  img.close?.();

  return new Promise((resolve) => {
    let result = null;
    const dialog = openDialog(`
      <h3>✂️ Ritaglia lo scontrino</h3>
      <p class="muted small">Trascina gli angoli verdi o sposta il riquadro.</p>
      <div class="crop-wrap"><div class="crop-stage">
        <canvas class="crop-canvas"></canvas>
        <div class="crop-box">
          <span class="crop-handle" data-h="tl"></span><span class="crop-handle" data-h="tr"></span>
          <span class="crop-handle" data-h="bl"></span><span class="crop-handle" data-h="br"></span>
        </div>
      </div></div>
      <div class="dialog-actions">
        <button type="button" class="btn secondary" data-rotate>↻ Ruota</button>
        <button type="button" class="btn secondary" data-full>Usa intera</button>
        <button type="button" class="btn primary" data-crop>✂️ Ritaglia</button>
      </div>
      <button type="button" class="btn secondary" data-close>Annulla</button>`, {
      onClose: () => {
        releaseCanvas(work);
        resolve(result);
      }
    });
    dialog.classList.add('wide');

    const canvas = dialog.querySelector('.crop-canvas');
    const box = dialog.querySelector('.crop-box');
    const stage = dialog.querySelector('.crop-stage');
    // Riquadro in frazioni (0-1) della foto: indipendente dalla dimensione a schermo
    let rect;

    function drawPreview() {
      const scale = Math.min(1, DISPLAY_MAX / Math.max(work.width, work.height));
      canvas.width = Math.round(work.width * scale);
      canvas.height = Math.round(work.height * scale);
      canvas.getContext('2d').drawImage(work, 0, 0, canvas.width, canvas.height);
      rect = { x: 0.05, y: 0.05, w: 0.9, h: 0.9 };
      placeBox();
    }

    function placeBox() {
      box.style.left = `${rect.x * 100}%`;
      box.style.top = `${rect.y * 100}%`;
      box.style.width = `${rect.w * 100}%`;
      box.style.height = `${rect.h * 100}%`;
    }

    let drag = null;
    stage.addEventListener('pointerdown', (e) => {
      const r = canvas.getBoundingClientRect();
      const handle = e.target.dataset?.h ?? (e.target === box ? 'move' : null);
      if (!handle) return;
      e.preventDefault();
      stage.setPointerCapture(e.pointerId);
      drag = { handle, startX: e.clientX, startY: e.clientY, start: { ...rect }, w: r.width, h: r.height };
    });
    stage.addEventListener('pointermove', (e) => {
      if (!drag) return;
      const dx = (e.clientX - drag.startX) / drag.w;
      const dy = (e.clientY - drag.startY) / drag.h;
      const minW = MIN_BOX / drag.w;
      const minH = MIN_BOX / drag.h;
      const s = drag.start;
      let { x, y, w, h } = s;
      if (drag.handle === 'move') {
        x = Math.min(Math.max(0, s.x + dx), 1 - s.w);
        y = Math.min(Math.max(0, s.y + dy), 1 - s.h);
      } else {
        if (drag.handle.includes('l')) {
          x = Math.min(Math.max(0, s.x + dx), s.x + s.w - minW);
          w = s.x + s.w - x;
        }
        if (drag.handle.includes('r')) w = Math.min(Math.max(minW, s.w + dx), 1 - s.x);
        if (drag.handle.includes('t')) {
          y = Math.min(Math.max(0, s.y + dy), s.y + s.h - minH);
          h = s.y + s.h - y;
        }
        if (drag.handle.includes('b')) h = Math.min(Math.max(minH, s.h + dy), 1 - s.y);
      }
      rect = { x, y, w, h };
      placeBox();
    });
    const endDrag = () => { drag = null; };
    stage.addEventListener('pointerup', endDrag);
    stage.addEventListener('pointercancel', endDrag);

    async function finish(crop) {
      const out = drawToCanvas(work, { crop });
      try {
        result = await canvasToJpeg(out);
      } finally {
        releaseCanvas(out);
      }
      dialog.close();
    }

    dialog.querySelector('[data-rotate]').addEventListener('click', () => {
      const rotated = drawToCanvas(work, { rotation: 90 });
      releaseCanvas(work);
      work = rotated;
      drawPreview();
    });
    dialog.querySelector('[data-full]').addEventListener('click', () => finish(null));
    dialog.querySelector('[data-crop]').addEventListener('click', () => finish({
      x: Math.round(rect.x * work.width),
      y: Math.round(rect.y * work.height),
      width: Math.max(1, Math.round(rect.w * work.width)),
      height: Math.max(1, Math.round(rect.h * work.height))
    }));

    drawPreview();
  });
}
