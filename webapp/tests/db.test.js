import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, mkdtempSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import initSqlJs from 'sql.js';
import {
  createDatabase, openDatabase, countSpese, listSpese, speseForMonth, insertSpesa, updateSpesa,
  deleteSpesa, exportDatabase, DbError
} from '../src/core/dbcore.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const fixture = () => new Uint8Array(readFileSync(path.join(here, 'fixtures', 'android_room_backup.db')));
const SQL = await initSqlJs();

/** Legge il file come fa l'app Android durante il ripristino (richiede python3). */
function readLikeAndroid(bytes) {
  const dir = mkdtempSync(path.join(tmpdir(), 'notespese-'));
  const file = path.join(dir, 'backup.db');
  writeFileSync(file, bytes);
  const out = execFileSync('python3', [path.join(here, '..', 'scripts', 'android_import_check.py'), file], { encoding: 'utf8' });
  return JSON.parse(out);
}

let hasPython = true;
try {
  execFileSync('python3', ['--version']);
} catch {
  hasPython = false;
}

test('apre un backup Android in modalità WAL', () => {
  const db = openDatabase(SQL, fixture());
  const spese = listSpese(db);
  assert.equal(spese.length, 3);
  assert.equal(spese[0].data, '2026-02-02');
  assert.equal(spese[2].destinazione, 'Autogrill Àèìòù');
  assert.equal(spese[2].note, 'Pranzo 🍝');
  assert.equal(speseForMonth(db, 2026, 1).length, 2);
});

test('rifiuta file non validi, senza tabella o vuoti', () => {
  assert.throws(() => openDatabase(SQL, new TextEncoder().encode('non un database'.repeat(10))), (e) => e instanceof DbError && e.code === 'INVALID_FILE');
  const other = new SQL.Database();
  other.run('CREATE TABLE altro (x)');
  assert.throws(() => openDatabase(SQL, other.export()), (e) => e.code === 'NO_EXPENSES_TABLE');
  const empty = createDatabase(SQL);
  assert.throws(() => openDatabase(SQL, empty.export(), { requireExpenses: true }), (e) => e.code === 'EMPTY');
  assert.equal(countSpese(openDatabase(SQL, empty.export())), 0);
});

test('modifiche della WebApp leggibili dall\'app Android', { skip: !hasPython && 'python3 non disponibile' }, () => {
  const db = openDatabase(SQL, fixture());
  const id = insertSpesa(db, {
    data: '2026-03-10', destinazione: 'Parcheggio Centro', scopo: '', categoria: 'PARCHEGGI_CC',
    metodo_pagamento: 'CC (Carta)', importo: 4.5, note: 'iPhone 📱', valuta_straniera: true
  }, new Date(2026, 2, 10, 8, 30, 0));
  assert.equal(id, 4);
  updateSpesa(db, 2, { data: '2026-01-21', destinazione: 'Telepass A1', scopo: null, importo: 13, note: '' });
  deleteSpesa(db, 3);

  const rows = readLikeAndroid(exportDatabase(db));
  assert.equal(rows.length, 3);
  const added = rows.find((r) => r.id === 4);
  assert.deepEqual(added, {
    id: 4, data: '2026-03-10', destinazione: 'Parcheggio Centro', scopo: null, categoria: 'PARCHEGGI_CC',
    metodo_pagamento: 'CC (Carta)', importo: 4.5, km: 0, note: 'iPhone 📱', allegato_path: null,
    valuta_straniera: 1, created_at: '2026-03-10 08:30:00'
  });
  const edited = rows.find((r) => r.id === 2);
  assert.equal(edited.data, '2026-01-21');
  assert.equal(edited.importo, 13);
  assert.equal(edited.note, null);
  assert.equal(rows.find((r) => r.id === 1).allegato_path, 'dropbox:/Allegati/2026/01/rec_2026-01-15_20260115_130000.jpg');
});

test('database nuovo della WebApp leggibile dall\'app Android', { skip: !hasPython && 'python3 non disponibile' }, () => {
  const db = createDatabase(SQL);
  insertSpesa(db, { data: '2026-04-01', categoria: 'ALTRO', metodo_pagamento: 'Contanti', importo: 1 });
  const rows = readLikeAndroid(exportDatabase(db));
  assert.equal(rows.length, 1);
  assert.equal(rows[0].categoria, 'ALTRO');
  // e la WebApp riapre il proprio file
  assert.equal(countSpese(openDatabase(SQL, exportDatabase(db))), 1);
});
