// Accesso al database SQLite con sql.js. Lo schema è identico a quello creato da Room
// nell'app Android, così lo stesso file .db passa da un dispositivo all'altro.
import { createdAtTimestamp } from './formats.js';

export const SPESE_SCHEMA = 'CREATE TABLE IF NOT EXISTS `spese` (' +
  '`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `data` TEXT NOT NULL, `destinazione` TEXT, ' +
  '`scopo` TEXT, `categoria` TEXT NOT NULL, `metodo_pagamento` TEXT, `importo` REAL NOT NULL, ' +
  '`km` REAL NOT NULL, `note` TEXT, `allegato_path` TEXT, `valuta_straniera` INTEGER NOT NULL, ' +
  '`created_at` TEXT)';

const SQLITE_HEADER = 'SQLite format 3\u0000';

export class DbError extends Error {
  constructor(code, message) {
    super(message);
    this.code = code;
  }
}

export const DB_ERRORS = {
  INVALID_FILE: 'Il file non è un database SQLite valido, oppure è danneggiato.',
  NO_EXPENSES_TABLE: 'Il file non contiene la tabella delle spese: probabilmente è un backup incompleto, creato da una versione precedente dell\'app Android. Prova un backup più grande o più recente.',
  EMPTY: 'Il file non contiene nessuna spesa: è un backup vuoto.'
};

export function isSqliteFile(bytes) {
  if (!bytes || bytes.length < 100) return false;
  for (let i = 0; i < SQLITE_HEADER.length; i++) {
    if (bytes[i] !== SQLITE_HEADER.charCodeAt(i)) return false;
  }
  return true;
}

/**
 * I database di Room sono in modalità WAL (byte 18 e 19 dell'intestazione = 2). L'app Android
 * fa un checkpoint prima del backup, quindi tutti i dati sono nel file principale: basta
 * riportare l'intestazione alla modalità classica (1) perché sql.js lo apra in memoria.
 */
export function withoutWalHeader(bytes) {
  const copy = new Uint8Array(bytes);
  if (copy[18] === 2) copy[18] = 1;
  if (copy[19] === 2) copy[19] = 1;
  return copy;
}

function hasSpeseTable(db) {
  const res = db.exec("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'spese'");
  return res.length > 0;
}

/** Database nuovo e vuoto. */
export function createDatabase(SQL) {
  const db = new SQL.Database();
  db.run(SPESE_SCHEMA);
  return db;
}

/**
 * Apre un file .db (backup Android o della WebApp). Con [requireExpenses] rifiuta i backup
 * senza spese, come fa l'app Android durante il ripristino.
 */
export function openDatabase(SQL, bytes, { requireExpenses = false } = {}) {
  if (!isSqliteFile(bytes)) throw new DbError('INVALID_FILE', DB_ERRORS.INVALID_FILE);
  let db;
  try {
    db = new SQL.Database(withoutWalHeader(bytes));
    db.exec('PRAGMA schema_version');
  } catch (e) {
    db?.close();
    throw new DbError('INVALID_FILE', DB_ERRORS.INVALID_FILE);
  }
  if (!hasSpeseTable(db)) {
    db.close();
    throw new DbError('NO_EXPENSES_TABLE', DB_ERRORS.NO_EXPENSES_TABLE);
  }
  if (requireExpenses && countSpese(db) === 0) {
    db.close();
    throw new DbError('EMPTY', DB_ERRORS.EMPTY);
  }
  return db;
}

function rows(db, sql, params = []) {
  const stmt = db.prepare(sql);
  try {
    stmt.bind(params);
    const out = [];
    while (stmt.step()) out.push(stmt.getAsObject());
    return out;
  } finally {
    stmt.free();
  }
}

export function countSpese(db) {
  return rows(db, 'SELECT COUNT(*) AS n FROM spese')[0].n;
}

export function listSpese(db) {
  return rows(db, 'SELECT * FROM spese ORDER BY data DESC, id DESC');
}

export function speseForMonth(db, year, month) {
  const start = `${year}-${String(month).padStart(2, '0')}-01`;
  const end = month === 12 ? `${year + 1}-01-01` : `${year}-${String(month + 1).padStart(2, '0')}-01`;
  return rows(db, 'SELECT * FROM spese WHERE data >= ? AND data < ? ORDER BY data ASC, id ASC', [start, end]);
}

export function getSpesa(db, id) {
  return rows(db, 'SELECT * FROM spese WHERE id = ?', [id])[0] ?? null;
}

const nullIfBlank = (v) => (v == null || String(v).trim() === '' ? null : String(v).trim());

/** Inserisce una spesa e ne restituisce l'id. */
export function insertSpesa(db, spesa, now = new Date()) {
  db.run(
    'INSERT INTO spese (data, destinazione, scopo, categoria, metodo_pagamento, importo, km, note, ' +
    'allegato_path, valuta_straniera, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
    [
      spesa.data,
      nullIfBlank(spesa.destinazione),
      nullIfBlank(spesa.scopo),
      spesa.categoria,
      spesa.metodo_pagamento ?? null,
      Number(spesa.importo) || 0,
      Number(spesa.km) || 0,
      nullIfBlank(spesa.note),
      spesa.allegato_path ?? null,
      spesa.valuta_straniera ? 1 : 0,
      createdAtTimestamp(now)
    ]
  );
  return rows(db, 'SELECT last_insert_rowid() AS id')[0].id;
}

/** Modifica i campi modificabili dalla scheda Registri (come nell'app Android). */
export function updateSpesa(db, id, { data, destinazione, scopo, importo, note }) {
  db.run(
    'UPDATE spese SET data = ?, destinazione = ?, scopo = ?, importo = ?, note = ? WHERE id = ?',
    [data, nullIfBlank(destinazione), nullIfBlank(scopo), Number(importo) || 0, nullIfBlank(note), id]
  );
}

export function deleteSpesa(db, id) {
  db.run('DELETE FROM spese WHERE id = ?', [id]);
}

/** Il file .db completo, pronto per il backup o il salvataggio locale. */
export function exportDatabase(db) {
  return db.export();
}
