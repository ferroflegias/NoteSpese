// Regole dei backup condivise con l'app Android (data/DropboxService.kt).
import { formatDateTime, sanitizeDeviceName } from './formats.js';

export const BACKUPS_FOLDER = '/Backups';
/** Copie dei dati locali fatte prima di un ripristino: in una cartella a parte, così non
 *  sembrano mai "l'ultimo backup" agli altri dispositivi. */
export const SAFETY_BACKUPS_FOLDER = '/Backups_PreRipristino';
export const BACKUP_RETENTION_MS = 30 * 24 * 60 * 60 * 1000;

const BACKUP_FILE_PREFIX = 'expense_report_backup_';
const BACKUP_NAME_REGEX = /^expense_report_backup_\d{8}_\d{6}_(.+)\.db$/;

/** es. expense_report_backup_20260131_142500_iPhone.db */
export function backupFileName(timestamp, deviceName) {
  return `${BACKUP_FILE_PREFIX}${timestamp}_${sanitizeDeviceName(deviceName)}.db`;
}

/** Dispositivo che ha creato il backup (null per i backup delle versioni precedenti). */
export function deviceFromBackupName(name) {
  return BACKUP_NAME_REGEX.exec(name ?? '')?.[1] ?? null;
}

/** True quando sul dispositivo ci sono modifiche non ancora salvate in un backup. */
export function hasUnsavedChanges(syncState) {
  return (syncState?.lastLocalChangeMs ?? 0) > (syncState?.lastSyncMs ?? 0);
}

/**
 * Motivi per cui ripristinare [chosen] potrebbe far perdere dati (lista vuota = ripristino sicuro).
 * [backups]: backup normali dal più recente, seguiti dalle copie di sicurezza.
 */
export function restoreWarnings({ syncState, backups, chosen, hasLocalData }) {
  const warnings = [];
  if (hasUnsavedChanges(syncState)) {
    warnings.push('Su questo dispositivo ci sono modifiche non ancora salvate in un backup: andrebbero perse (resterebbero solo nella copia di sicurezza).');
  } else if (!(syncState?.lastSyncMs > 0) && hasLocalData) {
    warnings.push('Su questo dispositivo non risulta ancora nessun backup o ripristino: i dati attuali potrebbero non essere in nessun backup.');
  }
  const newest = backups.find((b) => !b.isSafetyBackup);
  if (chosen.isSafetyBackup) {
    warnings.push('Hai scelto una copia di sicurezza fatta prima di un ripristino, non un backup normale.');
  } else if (newest && newest.path !== chosen.path) {
    const from = newest.deviceName ? ` da ${newest.deviceName}` : '';
    warnings.push(`Esiste un backup più recente (${formatDateTime(newest.modified)}${from}): quello scelto è più vecchio.`);
  }
  return warnings;
}

/**
 * Il backup più recente, se è stato fatto dopo l'ultimo allineamento di questo dispositivo:
 * va ripristinato prima di cominciare a lavorare.
 */
export function newerBackupToRestore(backups, syncState) {
  const newest = backups.find((b) => !b.isSafetyBackup);
  if (!newest?.modified) return null;
  return newest.modified.getTime() > (syncState?.lastSyncMs ?? 0) ? newest : null;
}

/** Cartelle "AAAA" e "MM" degli allegati di una data (come attachmentFolderParts su Android). */
export function attachmentFolderParts(dataSpesa) {
  const year = dataSpesa?.length >= 4 ? dataSpesa.slice(0, 4) : '0000';
  const month = dataSpesa?.length >= 7 ? dataSpesa.slice(5, 7) : '00';
  return [year, month];
}

/** Tipo di allegato salvato nella colonna allegato_path. */
export function attachmentKind(path) {
  if (!path) return 'none';
  if (path.startsWith('dropbox:')) return 'dropbox';
  if (path.startsWith('webapp-local:')) return 'local';
  if (/^https?:\/\//i.test(path)) return 'link';
  return 'device';
}
