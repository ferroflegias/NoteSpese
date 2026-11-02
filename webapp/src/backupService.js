// Backup e ripristino su Dropbox, con le stesse cartelle e gli stessi nomi dell'app Android.
import * as dropbox from './dropbox.js';
import {
  BACKUPS_FOLDER, SAFETY_BACKUPS_FOLDER, BACKUP_RETENTION_MS, backupFileName, deviceFromBackupName
} from './core/backups.js';
import { fileTimestamp } from './core/formats.js';

function toEntry(file, isSafetyBackup) {
  return {
    name: file.name,
    path: file.path_lower,
    size: file.size,
    modified: file.server_modified ? new Date(file.server_modified) : null,
    isSafetyBackup,
    deviceName: deviceFromBackupName(file.name)
  };
}

const newestFirst = (a, b) => (b.modified?.getTime() ?? 0) - (a.modified?.getTime() ?? 0);

/** Backup normali dal più recente, seguiti dalle copie di sicurezza. */
export async function listBackups() {
  const [regular, safety] = await Promise.all([
    dropbox.listFiles(BACKUPS_FOLDER),
    dropbox.listFiles(SAFETY_BACKUPS_FOLDER)
  ]);
  return [
    ...regular.map((f) => toEntry(f, false)).sort(newestFirst),
    ...safety.map((f) => toEntry(f, true)).sort(newestFirst)
  ];
}

async function cleanupOldBackups(folder) {
  try {
    const threshold = Date.now() - BACKUP_RETENTION_MS;
    const old = (await dropbox.listFiles(folder))
      .filter((f) => f.server_modified && new Date(f.server_modified).getTime() < threshold);
    for (const f of old) {
      try {
        await dropbox.deleteFile(f.path_lower);
      } catch {
        // riprova al prossimo backup
      }
    }
  } catch {
    // la pulizia non deve far fallire il backup
  }
}

/** Carica il backup e restituisce l'ora (del server Dropbox) in cui è stato salvato. */
export async function uploadBackup(bytes, deviceName, { safety = false } = {}) {
  const folder = safety ? SAFETY_BACKUPS_FOLDER : BACKUPS_FOLDER;
  const meta = await dropbox.uploadFile(`${folder}/${backupFileName(fileTimestamp(), deviceName)}`, bytes);
  await cleanupOldBackups(folder);
  return meta.server_modified ? new Date(meta.server_modified).getTime() : Date.now();
}

export const downloadBackup = (path) => dropbox.downloadFile(path);
