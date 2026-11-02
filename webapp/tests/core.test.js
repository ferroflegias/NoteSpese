import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  isValidIsoDate, parseAmount, formatAmount, fileTimestamp, sanitizeDeviceName
} from '../src/core/formats.js';
import {
  categoryKey, paymentLabel, monthTotals, RISTORANTI, PARCHEGGI, CARBURANTE, TELEPASS, NOLO, ALTRO,
  PAYMENT_CC, PAYMENT_CASH, PAYMENT_FUEL_CARD
} from '../src/core/categories.js';
import {
  backupFileName, deviceFromBackupName, restoreWarnings, newerBackupToRestore, attachmentKind
} from '../src/core/backups.js';

test('date e importi come nell\'app Android', () => {
  assert.ok(isValidIsoDate('2026-02-28'));
  assert.ok(!isValidIsoDate('2026-02-30'));
  assert.ok(!isValidIsoDate('28/02/2026'));
  assert.equal(parseAmount('12,50'), 12.5);
  assert.equal(parseAmount(' 7.3 '), 7.3);
  assert.equal(parseAmount('abc'), null);
  assert.equal(parseAmount(''), null);
  assert.equal(formatAmount(3), '3.00');
  assert.equal(fileTimestamp(new Date(2026, 0, 31, 14, 25, 0)), '20260131_142500');
});

test('nomi dei dispositivi e dei backup identici all\'app Android', () => {
  assert.equal(sanitizeDeviceName('Pixel 8 Pro'), 'Pixel-8-Pro');
  assert.equal(sanitizeDeviceName('  iPhone di Mario! '), 'iPhone-di-Mario');
  assert.equal(sanitizeDeviceName('   '), 'Dispositivo');
  assert.equal(sanitizeDeviceName('a'.repeat(50)).length, 30);
  const name = backupFileName('20260131_142500', 'iPhone 15');
  assert.equal(name, 'expense_report_backup_20260131_142500_iPhone-15.db');
  assert.equal(deviceFromBackupName(name), 'iPhone-15');
  assert.equal(deviceFromBackupName('expense_report_backup_20260131_142500.db'), null);
});

test('chiavi di categoria e pagamento identiche all\'app Android', () => {
  assert.equal(categoryKey(RISTORANTI, PAYMENT_CC), 'RISTORANTI_CC');
  assert.equal(categoryKey(RISTORANTI, PAYMENT_CASH), 'RISTORANTI_CONTANTI');
  assert.equal(categoryKey(PARCHEGGI, PAYMENT_FUEL_CARD), 'PARCHEGGI_CONTANTI');
  assert.equal(categoryKey(CARBURANTE, PAYMENT_FUEL_CARD), 'CARBURANTE_CARTA');
  assert.equal(categoryKey(CARBURANTE, PAYMENT_CASH), 'CARBURANTE_CC');
  assert.equal(categoryKey(TELEPASS, PAYMENT_CC), 'TELEPASS');
  assert.equal(categoryKey(NOLO, PAYMENT_CC), 'NOLO');
  assert.equal(categoryKey(ALTRO, PAYMENT_CC), 'ALTRO');
  assert.equal(paymentLabel(TELEPASS, PAYMENT_CC), null);
  assert.equal(paymentLabel(RISTORANTI, PAYMENT_CC), 'CC (Carta)');
  assert.equal(paymentLabel(RISTORANTI, PAYMENT_CASH), 'Contanti');
  assert.equal(paymentLabel(CARBURANTE, PAYMENT_FUEL_CARD), 'Carta Carburante');
});

test('totali del mese', () => {
  const t = monthTotals([
    { categoria: 'RISTORANTI_CC', metodo_pagamento: 'CC (Carta)', importo: 10 },
    { categoria: 'RISTORANTI_CC', metodo_pagamento: 'CC', importo: 5 },
    { categoria: 'TELEPASS', metodo_pagamento: null, importo: 3 },
    { categoria: 'CARBURANTE_CARTA', metodo_pagamento: 'Carta Carburante', importo: 50 },
    { categoria: 'RISTORANTI_CONTANTI', metodo_pagamento: 'Contanti', importo: 2 }
  ]);
  assert.equal(t.total, 70);
  assert.equal(t.cc, 15);
  assert.equal(t.cash, 2);
  assert.equal(t.fuelCard, 50);
  assert.equal(t.telepass, 3);
  assert.deepEqual(t.byCategory.get('RISTORANTI_CC'), { total: 15, count: 2 });
});

const backups = [
  { name: 'b3', path: '/backups/b3', modified: new Date('2026-03-03T10:00:00Z'), isSafetyBackup: false, deviceName: 'Pixel-8' },
  { name: 'b2', path: '/backups/b2', modified: new Date('2026-03-02T10:00:00Z'), isSafetyBackup: false, deviceName: 'iPhone' },
  { name: 's1', path: '/backups_preripristino/s1', modified: new Date('2026-03-04T10:00:00Z'), isSafetyBackup: true, deviceName: 'iPhone' }
];

test('avvisi prima di un ripristino rischioso', () => {
  const synced = { lastLocalChangeMs: 1, lastSyncMs: 2 };
  assert.deepEqual(restoreWarnings({ syncState: synced, backups, chosen: backups[0], hasLocalData: true }), []);
  const unsaved = restoreWarnings({ syncState: { lastLocalChangeMs: 5, lastSyncMs: 2 }, backups, chosen: backups[0], hasLocalData: true });
  assert.equal(unsaved.length, 1);
  assert.match(unsaved[0], /non ancora salvate/);
  const older = restoreWarnings({ syncState: synced, backups, chosen: backups[1], hasLocalData: true });
  assert.equal(older.length, 1);
  assert.match(older[0], /più recente .*Pixel-8/);
  const safety = restoreWarnings({ syncState: synced, backups, chosen: backups[2], hasLocalData: true });
  assert.match(safety[0], /copia di sicurezza/);
  const neverSynced = restoreWarnings({ syncState: { lastLocalChangeMs: 0, lastSyncMs: 0 }, backups, chosen: backups[0], hasLocalData: true });
  assert.match(neverSynced[0], /nessun backup o ripristino/);
  assert.deepEqual(restoreWarnings({ syncState: { lastLocalChangeMs: 0, lastSyncMs: 0 }, backups, chosen: backups[0], hasLocalData: false }), []);
});

test('backup più recente da ripristinare all\'avvio', () => {
  assert.equal(newerBackupToRestore(backups, { lastSyncMs: Date.parse('2026-03-01T00:00:00Z') }), backups[0]);
  assert.equal(newerBackupToRestore(backups, { lastSyncMs: Date.parse('2026-03-03T10:00:00Z') }), null);
  assert.equal(newerBackupToRestore([], { lastSyncMs: 0 }), null);
});

test('tipi di allegato', () => {
  assert.equal(attachmentKind(null), 'none');
  assert.equal(attachmentKind('dropbox:/Allegati/2026/01/a.jpg'), 'dropbox');
  assert.equal(attachmentKind('https://drive.google.com/x'), 'link');
  assert.equal(attachmentKind('/data/user/0/app/files/a.jpg'), 'device');
});
