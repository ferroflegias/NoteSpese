// Allegati: su Dropbox (stessa cartella e stessi nomi dell'app Android) oppure, se Dropbox non è
// collegato o non raggiungibile, salvati su questo dispositivo finché non vengono caricati.
import * as dropbox from './dropbox.js';
import { kvGet, kvSet, kvDelete } from './storage.js';
import { fileTimestamp } from './core/formats.js';
import { attachmentFolderParts } from './core/backups.js';

export const LOCAL_PREFIX = 'webapp-local:';

const isDropbox = (path) => path?.startsWith(dropbox.PATH_PREFIX);
export const isLocalOnly = (path) => path?.startsWith(LOCAL_PREFIX);

function relativePath(dataSpesa, ext) {
  const [year, month] = attachmentFolderParts(dataSpesa);
  return `/Allegati/${year}/${month}/rec_${dataSpesa}_${fileTimestamp()}.${ext}`;
}

async function uploadToDropbox(bytes, dataSpesa, ext) {
  const meta = await dropbox.uploadFile(relativePath(dataSpesa, ext), bytes);
  return dropbox.PATH_PREFIX + (meta.path_display ?? meta.path_lower);
}

/**
 * Salva l'allegato e restituisce il percorso da scrivere nel database.
 * [uploaded] è false quando l'allegato è rimasto solo su questo dispositivo.
 */
export async function saveAttachment(bytes, { isPdf, dataSpesa, dropboxConnected }) {
  const ext = isPdf ? 'pdf' : 'jpg';
  if (dropboxConnected) {
    try {
      return { path: await uploadToDropbox(bytes, dataSpesa, ext), uploaded: true };
    } catch {
      // offline o errore: resta sul dispositivo
    }
  }
  const path = LOCAL_PREFIX + relativePath(dataSpesa, ext);
  await kvSet(`att:${path}`, bytes);
  return { path, uploaded: false };
}

export async function readAttachment(path) {
  if (isDropbox(path)) return dropbox.downloadFile(path.slice(dropbox.PATH_PREFIX.length));
  if (isLocalOnly(path)) return (await kvGet(`att:${path}`)) ?? null;
  return null;
}

export async function deleteAttachment(path) {
  if (isDropbox(path)) return dropbox.deleteFile(path.slice(dropbox.PATH_PREFIX.length));
  if (isLocalOnly(path)) return kvDelete(`att:${path}`);
}

/**
 * Carica su Dropbox gli allegati rimasti su questo dispositivo e aggiorna le spese
 * con [updatePath](id, nuovoPercorso).
 */
export async function uploadLocalAttachments(spese, updatePath) {
  let uploaded = 0;
  let failed = 0;
  for (const s of spese.filter((x) => isLocalOnly(x.allegato_path))) {
    try {
      const bytes = await kvGet(`att:${s.allegato_path}`);
      if (!bytes) {
        failed++;
        continue;
      }
      const ext = s.allegato_path.toLowerCase().endsWith('.pdf') ? 'pdf' : 'jpg';
      const newPath = await uploadToDropbox(bytes, s.data, ext);
      await updatePath(s.id, newPath);
      await kvDelete(`att:${s.allegato_path}`);
      uploaded++;
    } catch {
      failed++;
    }
  }
  return { uploaded, failed };
}
