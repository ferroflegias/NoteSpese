import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import JSZip from 'jszip';
import { DOMParser } from '@xmldom/xmldom';
import { fillExcelTemplate, excelFileName, EXCEL_MODES, columnLetter, columnIndex, isXlsx, templateSheetNames } from '../src/core/excelExport.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const template = new Uint8Array(readFileSync(path.join(here, '..', '..', 'modello', 'Note spese 2026.xlsx')));

const spese = {
  1: [
    { data: '2026-01-05', destinazione: 'Milano', scopo: 'Cliente A', categoria: 'RISTORANTI_CC', importo: 12.5, note: 'pranzo', valuta_straniera: 0 },
    { data: '2026-01-05', destinazione: 'Milano', scopo: 'Cliente B', categoria: 'RISTORANTI_CC', importo: 7.3, note: 'cena', valuta_straniera: 0 },
    { data: '2026-01-05', destinazione: 'Monza', scopo: null, categoria: 'TELEPASS', importo: 4, note: null, valuta_straniera: 0 },
    { data: '2026-01-20', destinazione: 'Zurigo', scopo: 'Fiera', categoria: 'CARBURANTE_CARTA', importo: 60, note: null, valuta_straniera: 1 },
    { data: '2026-01-20', destinazione: 'Zurigo', scopo: 'Fiera', categoria: 'CARBURANTE_CARTA', importo: 10, note: null, valuta_straniera: 0 },
    { data: '2026-01-31', destinazione: 'Città & Co <Srl>', scopo: null, categoria: 'ALTRO', importo: 1.05, note: 'àèì', valuta_straniera: 0 }
  ],
  2: [{ data: '2026-02-28', destinazione: 'Roma', scopo: null, categoria: 'NOLO', importo: 100, note: null, valuta_straniera: 0 }]
};

async function readSheet(bytes, index) {
  const zip = await JSZip.loadAsync(bytes);
  const sst = new DOMParser().parseFromString(await zip.file('xl/sharedStrings.xml').async('string'), 'application/xml');
  const strings = [...sst.getElementsByTagName('si')].map((si) => [...si.getElementsByTagName('t')].map((t) => t.textContent).join(''));
  const doc = new DOMParser().parseFromString(await zip.file(`xl/worksheets/sheet${index}.xml`).async('string'), 'application/xml');
  const cells = {};
  for (const c of doc.getElementsByTagName('c')) {
    const f = c.getElementsByTagName('f')[0];
    const v = c.getElementsByTagName('v')[0];
    cells[c.getAttribute('r')] = {
      s: Number(c.getAttribute('s') || 0),
      f: f?.textContent,
      value: c.getAttribute('t') === 's' ? strings[Number(v.textContent)] : v?.textContent
    };
  }
  return { zip, cells };
}

test('utilità', async () => {
  assert.equal(columnLetter(0), 'A');
  assert.equal(columnLetter(13), 'N');
  assert.equal(columnLetter(27), 'AB');
  assert.equal(columnIndex('AB12'), 27);
  assert.ok(isXlsx(template));
  assert.equal((await templateSheetNames(template))[0], 'Gennaio');
  assert.equal(excelFileName(EXCEL_MODES.SINGLE_MONTH, 2026, 1, 1), 'Note_Spese_Gennaio_2026.xlsx');
  assert.equal(excelFileName(EXCEL_MODES.MONTH_RANGE, 2026, 1, 3), 'Note_Spese_Gennaio_Marzo_2026.xlsx');
  assert.equal(excelFileName(EXCEL_MODES.FULL_YEAR, 2026, 1, 12), 'Note_Spese_Anno_2026.xlsx');
});

test('compila il modello come l\'app Android', async () => {
  const out = await fillExcelTemplate(template, { startMonth: 1, endMonth: 2, speseForMonth: (m) => spese[m] ?? [] });
  mkdirSync(path.join(here, 'output'), { recursive: true });
  writeFileSync(path.join(here, 'output', 'Note_Spese_test.xlsx'), out);

  const { zip, cells } = await readSheet(out, 1); // Gennaio
  // giorno 5 -> riga 10
  assert.equal(cells.C10.value, 'Milano\\Monza');
  assert.equal(cells.D10.value, 'Cliente A\\Cliente B');
  assert.equal(cells.O10.value, 'pranzo; cena');
  assert.equal(cells.K10.f, '12.50+7.30');
  assert.equal(cells.F10.value, '4');
  assert.equal(cells.C10.s, 24); // stile del modello conservato
  // giorno 20 -> riga 25: valuta estera evidenziata (oro), poi somma evidenziata (giallo)
  assert.equal(cells.M25.f, '60.00+10.00');
  assert.equal(cells.C25.value, 'Zurigo');
  // giorno 31 -> riga 36
  assert.equal(cells.C36.value, 'Città & Co <Srl>');
  assert.equal(cells.N36.value, '1.05');
  assert.equal(cells.O36.value, 'àèì');

  const styles = await zip.file('xl/styles.xml').async('string');
  assert.match(styles, /<fgColor indexed="13"\/>/);
  const wbXml = await zip.file('xl/workbook.xml').async('string');
  assert.match(wbXml, /fullCalcOnLoad="1"/);
  assert.equal(zip.file('xl/calcChain.xml'), null);
  assert.doesNotMatch(await zip.file('[Content_Types].xml').async('string'), /calcChain/);
  assert.ok(zip.file('xl/printerSettings/printerSettings1.bin'), 'parti del modello conservate');

  const feb = await readSheet(out, 2);
  assert.equal(feb.cells.G33.value, '100'); // 28 febbraio -> riga 33, NOLO -> G
});
