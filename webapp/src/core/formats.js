// Formati condivisi con l'app Android (util/Formats.kt).

const ISO_DATE_REGEX = /^\d{4}-\d{2}-\d{2}$/;

const pad = (n, len = 2) => String(n).padStart(len, '0');

export function todayIsoDate(now = new Date()) {
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

/** True solo per date di calendario reali nel formato yyyy-MM-dd usato nel database. */
export function isValidIsoDate(value) {
  if (!ISO_DATE_REGEX.test(value ?? '')) return false;
  const [y, m, d] = value.split('-').map(Number);
  const date = new Date(Date.UTC(y, m - 1, d));
  return date.getUTCFullYear() === y && date.getUTCMonth() === m - 1 && date.getUTCDate() === d;
}

/** Importi scritti con la virgola o con il punto come separatore decimale. */
export function parseAmount(value) {
  const text = String(value ?? '').trim().replace(',', '.');
  if (text === '' || !/^[+-]?(\d+\.?\d*|\.\d+)$/.test(text)) return null;
  return Number(text);
}

export function formatAmount(value) {
  return (Number(value) || 0).toFixed(2);
}

/** Timestamp nei nomi di backup e allegati, es. 20260131_142500 (ora locale, come su Android). */
export function fileTimestamp(now = new Date()) {
  return `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}_` +
    `${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`;
}

/** Data e ora nel formato della colonna created_at: yyyy-MM-dd HH:mm:ss. */
export function createdAtTimestamp(now = new Date()) {
  return `${todayIsoDate(now)} ${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`;
}

export function formatDateTime(date) {
  if (!date) return '?';
  const d = date instanceof Date ? date : new Date(date);
  return `${pad(d.getDate())}/${pad(d.getMonth() + 1)}/${d.getFullYear()} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** Solo lettere, cifre e trattini: il nome del dispositivo finisce nel nome del file di backup. */
export function sanitizeDeviceName(name) {
  const cleaned = String(name ?? '')
    .trim()
    .replace(/[^A-Za-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 30)
    .replace(/^-+|-+$/g, '');
  return cleaned || 'Dispositivo';
}

export const MONTH_NAMES = [
  'Gennaio', 'Febbraio', 'Marzo', 'Aprile', 'Maggio', 'Giugno',
  'Luglio', 'Agosto', 'Settembre', 'Ottobre', 'Novembre', 'Dicembre'
];
