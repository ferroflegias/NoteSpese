// Stessi casi di ExpenseReport/app/src/test/.../OcrParsingTest.kt
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseReceiptText } from '../src/core/ocrParser.js';
import { categoryFromOcr, paymentFromOcr, RISTORANTI, CARBURANTE, ALTRO, PAYMENT_CC, PAYMENT_CASH, PAYMENT_FUEL_CARD } from '../src/core/categories.js';

const TODAY = '2026-11-02';
const parse = (text) => {
  const r = parseReceiptText(text, TODAY);
  assert.ok(r);
  return r;
};

test('testo vuoto', () => assert.equal(parseReceiptText('  \n ', TODAY), null));

test('scontrino del ristorante', () => {
  const r = parse(['Ristorante Da Mario', 'Via Roma 1 - Milano', 'Tel 02 123456', '31/03/2026 20:15',
    'Coperto 2,00', 'TOTALE EURO 45,50', 'Pagamento con carta'].join('\n'));
  assert.equal(r.dataStr, '2026-03-31');
  assert.equal(r.destinazione, 'Ristorante Da Mario');
  assert.equal(r.importo, 45.5);
  assert.equal(r.categoriaSuggerita, 'Bar/Rist/Alb');
  assert.equal(r.pagamentoSuggerito, 'CC');
});

test('date impossibili ignorate', () => {
  assert.equal(parse('Scontrino\n31/02/2026\n15.01.26\nTotale 3,00').dataStr, '2026-01-15');
  assert.equal(parse('Codice 99/99/9999\nTotale 3,00').dataStr, TODAY);
});

test('importo della riga totale', () => assert.equal(parse('Bar Centrale\nCodice 123,45\nTotale 7,80\nResto 2,20').importo, 7.8));

test('importo con il simbolo €', () => assert.equal(parse('Esercente\nCodice 99,99\nPagato 12,50€').importo, 12.5));

test('parole chiave intere', () => {
  const r = parse('Negozio Alfa\nbeni vari, posto 3, barcode 1234\nTotale 9,90');
  assert.equal(r.categoriaSuggerita, 'Altro');
  assert.equal(r.pagamentoSuggerito, 'Contanti');
});

test('carburante', () => {
  const r = parse('ENI Station\nGasolio 40,00\nTotale 40,00');
  assert.equal(r.categoriaSuggerita, 'Carburante');
  assert.equal(r.pagamentoSuggerito, 'Carta Carburante');
});

test('esercente non confuso con telefono o indirizzo', () => {
  assert.equal(parse('Hotel Bellavista\nTotale 80,00').destinazione, 'Hotel Bellavista');
  assert.equal(parse('Tel 0123 456789\nPasticceria Rossi\nTotale 2,00').destinazione, 'Pasticceria Rossi');
});

test('parole accentate senza distinzione di maiuscole', () => {
  assert.equal(parse('Pasticceria Rossi\nCAFFÈ 1,20\nTotale 1,20').categoriaSuggerita, 'Bar/Rist/Alb');
});

test('suggerimenti OCR -> chip', () => {
  assert.equal(categoryFromOcr('Bar/Rist/Alb'), RISTORANTI);
  assert.equal(categoryFromOcr('Carburante'), CARBURANTE);
  assert.equal(categoryFromOcr('boh'), ALTRO);
  assert.equal(paymentFromOcr('Contanti'), PAYMENT_CASH);
  assert.equal(paymentFromOcr('Carta Carburante'), PAYMENT_FUEL_CARD);
  assert.equal(paymentFromOcr('CC'), PAYMENT_CC);
});
