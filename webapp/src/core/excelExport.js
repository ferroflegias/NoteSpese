// Compilazione del modello Excel ufficiale: stesse regole dell'app Android
// (util/DocumentGenerator.kt, generateExcel). Il file .xlsx viene modificato direttamente
// nell'XML delle celle interessate, così formattazione, stampa e formule del modello restano intatte.
import JSZip from 'jszip';
import { DOMParser, XMLSerializer } from '@xmldom/xmldom';
import { formatAmount, MONTH_NAMES } from './formats.js';

const MAIN_NS = 'http://schemas.openxmlformats.org/spreadsheetml/2006/main';
const REL_NS = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships';

/** Colonna (0 = A) dell'importo per ogni categoria del database. */
export const CATEGORY_COLUMN_MAP = {
  TELEPASS: 5, // F
  NOLO: 6, // G
  PARCHEGGI_CONTANTI: 7, // H
  PARCHEGGI_CC: 8, // I
  RISTORANTI_CONTANTI: 9, // J
  RISTORANTI_CC: 10, // K
  CARBURANTE_CC: 11, // L
  CARBURANTE_CARTA: 12, // M
  ALTRO: 13 // N
};

const COL_DESTINAZIONE = 2; // C
const COL_SCOPO = 3; // D
const COL_NOTE = 14; // O
/** Il giorno 1 è sulla riga 6 del foglio. */
const FIRST_DAY_ROW = 6;
/** Colori indicizzati di Excel usati dall'app Android (IndexedColors.GOLD e YELLOW). */
export const GOLD = 51;
export const YELLOW = 13;

export const EXCEL_MODES = { SINGLE_MONTH: 'SINGLE_MONTH', MONTH_RANGE: 'MONTH_RANGE', FULL_YEAR: 'FULL_YEAR' };

export function excelFileName(mode, year, startMonth, endMonth) {
  if (mode === EXCEL_MODES.FULL_YEAR) return `Note_Spese_Anno_${year}.xlsx`;
  if (mode === EXCEL_MODES.MONTH_RANGE) return `Note_Spese_${MONTH_NAMES[startMonth - 1]}_${MONTH_NAMES[endMonth - 1]}_${year}.xlsx`;
  return `Note_Spese_${MONTH_NAMES[startMonth - 1]}_${year}.xlsx`;
}

export function columnLetter(index) {
  let n = index + 1;
  let s = '';
  while (n > 0) {
    const r = (n - 1) % 26;
    s = String.fromCharCode(65 + r) + s;
    n = Math.floor((n - 1) / 26);
  }
  return s;
}

export function columnIndex(ref) {
  const letters = /^[A-Z]+/.exec(ref)[0];
  let n = 0;
  for (const ch of letters) n = n * 26 + (ch.charCodeAt(0) - 64);
  return n - 1;
}

const children = (el, name) => {
  const out = [];
  for (let n = el?.firstChild; n; n = n.nextSibling) {
    if (n.nodeType === 1 && (n.localName ?? n.nodeName) === name) out.push(n);
  }
  return out;
};
const child = (el, name) => children(el, name)[0] ?? null;
const textOf = (el) => (el ? el.textContent ?? '' : '');

function parse(xml) {
  return new DOMParser().parseFromString(xml, 'application/xml');
}

function serialize(doc) {
  let xml = new XMLSerializer().serializeToString(doc);
  if (!xml.startsWith('<?xml')) xml = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n' + xml;
  return xml;
}

function resolveTarget(base, target) {
  if (target.startsWith('/')) return target.slice(1);
  const parts = base.split('/').slice(0, -1);
  for (const p of target.split('/')) {
    if (p === '..') parts.pop();
    else if (p !== '.') parts.push(p);
  }
  return parts.join('/');
}

/** Il modello aperto: fogli, stringhe condivise e stili. */
class Workbook {
  static async load(bytes) {
    const wb = new Workbook();
    wb.zip = await JSZip.loadAsync(bytes);
    const read = (path) => wb.zip.file(path)?.async('string');
    wb.workbookDoc = parse(await read('xl/workbook.xml'));
    const rels = parse(await read('xl/_rels/workbook.xml.rels'));
    const relTargets = {};
    for (const r of children(rels.documentElement, 'Relationship')) {
      relTargets[r.getAttribute('Id')] = { target: r.getAttribute('Target'), type: r.getAttribute('Type') };
    }
    wb.relsDoc = rels;
    wb.sheetPaths = {};
    for (const s of children(child(wb.workbookDoc.documentElement, 'sheets'), 'sheet')) {
      const rel = relTargets[s.getAttributeNS(REL_NS, 'id') || s.getAttribute('r:id')];
      if (rel) wb.sheetPaths[s.getAttribute('name')] = resolveTarget('xl/workbook.xml', rel.target);
    }
    // Stringhe condivise
    const sstXml = await read('xl/sharedStrings.xml');
    if (sstXml) {
      wb.sstDoc = parse(sstXml);
    } else {
      wb.sstDoc = parse(`<sst xmlns="${MAIN_NS}" count="0" uniqueCount="0"/>`);
      wb.zip.file('xl/sharedStrings.xml', '');
      wb.addPart('xl/sharedStrings.xml', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml',
        'http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings', 'sharedStrings.xml');
    }
    wb.strings = children(wb.sstDoc.documentElement, 'si').map((si) =>
      [...si.getElementsByTagNameNS(MAIN_NS, 't')].map(textOf).join(''));
    // Stili
    wb.stylesDoc = parse(await read('xl/styles.xml'));
    wb.cellXfs = child(wb.stylesDoc.documentElement, 'cellXfs');
    wb.fills = child(wb.stylesDoc.documentElement, 'fills');
    wb.highlightCache = new Map();
    wb.sheetDocs = new Map();
    return wb;
  }

  addPart(path, contentType, relType, target) {
    this.pendingParts ??= [];
    this.pendingParts.push({ path, contentType, relType, target });
  }

  async sheet(name) {
    const path = this.sheetPaths[name];
    if (!path) return null;
    if (!this.sheetDocs.has(path)) this.sheetDocs.set(path, parse(await this.zip.file(path).async('string')));
    return this.sheetDocs.get(path);
  }

  addSharedString(text) {
    const doc = this.sstDoc;
    const si = doc.createElementNS(MAIN_NS, 'si');
    const t = doc.createElementNS(MAIN_NS, 't');
    if (/^\s|\s$|\n/.test(text)) t.setAttribute('xml:space', 'preserve');
    t.appendChild(doc.createTextNode(text));
    si.appendChild(t);
    doc.documentElement.appendChild(si);
    this.strings.push(text);
    return this.strings.length - 1;
  }

  xf(index) {
    return children(this.cellXfs, 'xf')[index] ?? children(this.cellXfs, 'xf')[0];
  }

  /** Colore indicizzato dello sfondo dello stile della cella (0 se non indicizzato). */
  fillColorOf(cell) {
    const xf = this.xf(Number(cell.getAttribute('s') || 0));
    const fill = children(this.fills, 'fill')[Number(xf?.getAttribute('fillId') || 0)];
    const fg = child(child(fill, 'patternFill'), 'fgColor');
    return Number(fg?.getAttribute('indexed') || 0);
  }

  /** Copia lo stile della cella cambiando solo lo sfondo (come cloneStyleFrom dell'app Android). */
  highlight(cell, colorIndex) {
    const base = Number(cell.getAttribute('s') || 0);
    const key = `${base}:${colorIndex}`;
    if (!this.highlightCache.has(key)) {
      const doc = this.stylesDoc;
      const fill = doc.createElementNS(MAIN_NS, 'fill');
      const pattern = doc.createElementNS(MAIN_NS, 'patternFill');
      pattern.setAttribute('patternType', 'solid');
      const fg = doc.createElementNS(MAIN_NS, 'fgColor');
      fg.setAttribute('indexed', String(colorIndex));
      const bg = doc.createElementNS(MAIN_NS, 'bgColor');
      bg.setAttribute('indexed', '64');
      pattern.appendChild(fg);
      pattern.appendChild(bg);
      fill.appendChild(pattern);
      this.fills.appendChild(fill);
      const fillId = children(this.fills, 'fill').length - 1;
      this.fills.setAttribute('count', String(fillId + 1));

      const xf = this.xf(base).cloneNode(true);
      xf.setAttribute('fillId', String(fillId));
      xf.setAttribute('applyFill', '1');
      this.cellXfs.appendChild(xf);
      const xfId = children(this.cellXfs, 'xf').length - 1;
      this.cellXfs.setAttribute('count', String(xfId + 1));
      this.highlightCache.set(key, xfId);
    }
    cell.setAttribute('s', String(this.highlightCache.get(key)));
  }

  /** Tipo della cella come in Apache POI. */
  cellType(cell) {
    if (child(cell, 'f')) return 'FORMULA';
    const t = cell.getAttribute('t');
    if (t === 's' || t === 'inlineStr' || t === 'str') return 'STRING';
    if (t === 'b') return 'BOOLEAN';
    if (t === 'e') return 'ERROR';
    return child(cell, 'v') ? 'NUMERIC' : 'BLANK';
  }

  /** Testo della cella come getCellText dell'app Android. */
  cellText(cell) {
    switch (this.cellType(cell)) {
      case 'STRING': {
        const t = cell.getAttribute('t');
        if (t === 's') return (this.strings[Number(textOf(child(cell, 'v')))] ?? '').trim();
        if (t === 'inlineStr') return [...cell.getElementsByTagNameNS(MAIN_NS, 't')].map(textOf).join('').trim();
        return textOf(child(cell, 'v')).trim();
      }
      case 'NUMERIC': {
        const num = Number(textOf(child(cell, 'v')));
        return Number.isInteger(num) ? String(num) : formatAmount(num);
      }
      case 'BOOLEAN': return textOf(child(cell, 'v')) === '1' ? 'true' : 'false';
      case 'FORMULA': return textOf(child(cell, 'f')).trim();
      default: return '';
    }
  }

  clearValue(cell) {
    for (const name of ['f', 'v', 'is']) children(cell, name).forEach((n) => cell.removeChild(n));
    cell.removeAttribute('t');
  }

  setString(cell, text) {
    this.clearValue(cell);
    const doc = cell.ownerDocument;
    cell.setAttribute('t', 's');
    const v = doc.createElementNS(MAIN_NS, 'v');
    v.appendChild(doc.createTextNode(String(this.addSharedString(text))));
    cell.appendChild(v);
  }

  setNumber(cell, value) {
    this.clearValue(cell);
    const doc = cell.ownerDocument;
    const v = doc.createElementNS(MAIN_NS, 'v');
    v.appendChild(doc.createTextNode(String(value)));
    cell.appendChild(v);
  }

  setFormula(cell, formula) {
    this.clearValue(cell);
    const doc = cell.ownerDocument;
    const f = doc.createElementNS(MAIN_NS, 'f');
    f.appendChild(doc.createTextNode(formula));
    cell.appendChild(f);
  }

  /** Riga [rowNum] del foglio, creata (nella posizione corretta) se non esiste. */
  row(sheetDoc, rowNum) {
    const sheetData = sheetDoc.getElementsByTagNameNS(MAIN_NS, 'sheetData')[0];
    let before = null;
    for (const r of children(sheetData, 'row')) {
      const n = Number(r.getAttribute('r'));
      if (n === rowNum) return r;
      if (n > rowNum) {
        before = r;
        break;
      }
    }
    const row = sheetDoc.createElementNS(MAIN_NS, 'row');
    row.setAttribute('r', String(rowNum));
    sheetData.insertBefore(row, before);
    return row;
  }

  /** Cella della colonna [colIdx], creata (nella posizione corretta) se non esiste. */
  cell(row, colIdx) {
    const ref = columnLetter(colIdx) + row.getAttribute('r');
    let before = null;
    for (const c of children(row, 'c')) {
      const idx = columnIndex(c.getAttribute('r'));
      if (idx === colIdx) return c;
      if (idx > colIdx) {
        before = c;
        break;
      }
    }
    const c = row.ownerDocument.createElementNS(MAIN_NS, 'c');
    c.setAttribute('r', ref);
    row.insertBefore(c, before);
    return c;
  }

  /** Scrive [value] nella cella, accodandolo con [separator] se contiene già altri valori. */
  appendUniqueText(row, colIdx, value, separator) {
    const cell = this.cell(row, colIdx);
    const curr = this.cellText(cell);
    if (curr === '') {
      this.setString(cell, value);
    } else if (!curr.split(separator.trim()).map((s) => s.trim()).includes(value)) {
      this.setString(cell, `${curr}${separator}${value}`);
    }
  }

  async save() {
    for (const [path, doc] of this.sheetDocs) this.zip.file(path, serialize(doc));
    const sst = this.sstDoc.documentElement;
    sst.setAttribute('uniqueCount', String(this.strings.length));
    sst.setAttribute('count', String(Math.max(Number(sst.getAttribute('count') || 0), this.strings.length)));
    this.zip.file('xl/sharedStrings.xml', serialize(this.sstDoc));
    this.zip.file('xl/styles.xml', serialize(this.stylesDoc));

    // I totali del modello sono formule: Excel li ricalcola all'apertura (come setForceFormulaRecalculation).
    // La catena di calcolo viene eliminata: Excel la ricostruisce.
    const wbRoot = this.workbookDoc.documentElement;
    let calcPr = child(wbRoot, 'calcPr');
    if (!calcPr) {
      calcPr = this.workbookDoc.createElementNS(MAIN_NS, 'calcPr');
      const after = child(wbRoot, 'definedNames') ?? child(wbRoot, 'sheets');
      wbRoot.insertBefore(calcPr, after.nextSibling);
    }
    calcPr.setAttribute('fullCalcOnLoad', '1');
    this.zip.file('xl/workbook.xml', serialize(this.workbookDoc));

    for (const r of children(this.relsDoc.documentElement, 'Relationship')) {
      if (r.getAttribute('Type').endsWith('/calcChain')) this.relsDoc.documentElement.removeChild(r);
    }
    for (const p of this.pendingParts ?? []) {
      const rel = this.relsDoc.createElementNS('http://schemas.openxmlformats.org/package/2006/relationships', 'Relationship');
      rel.setAttribute('Id', `rIdNoteSpese${Math.random().toString(36).slice(2, 8)}`);
      rel.setAttribute('Type', p.relType);
      rel.setAttribute('Target', p.target);
      this.relsDoc.documentElement.appendChild(rel);
    }
    this.zip.file('xl/_rels/workbook.xml.rels', serialize(this.relsDoc));

    const ctDoc = parse(await this.zip.file('[Content_Types].xml').async('string'));
    for (const o of children(ctDoc.documentElement, 'Override')) {
      if (o.getAttribute('PartName') === '/xl/calcChain.xml') ctDoc.documentElement.removeChild(o);
    }
    for (const p of this.pendingParts ?? []) {
      const o = ctDoc.createElementNS('http://schemas.openxmlformats.org/package/2006/content-types', 'Override');
      o.setAttribute('PartName', `/${p.path}`);
      o.setAttribute('ContentType', p.contentType);
      ctDoc.documentElement.appendChild(o);
    }
    this.zip.file('[Content_Types].xml', serialize(ctDoc));
    this.zip.remove('xl/calcChain.xml');

    return this.zip.generateAsync({ type: 'uint8array', compression: 'DEFLATE', mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });
  }
}

/** True per un file .xlsx (archivio ZIP). */
export function isXlsx(bytes) {
  return bytes?.length > 4 && bytes[0] === 0x50 && bytes[1] === 0x4b;
}

/**
 * Compila una copia del modello con le spese dei mesi da [startMonth] a [endMonth].
 * [speseForMonth](m) restituisce le spese del mese in ordine di data e id.
 */
export async function fillExcelTemplate(templateBytes, { startMonth, endMonth, speseForMonth }) {
  const wb = await Workbook.load(templateBytes);
  for (let m = startMonth; m <= endMonth; m++) {
    const sheetDoc = await wb.sheet(MONTH_NAMES[m - 1]);
    if (!sheetDoc) continue;
    for (const spesa of speseForMonth(m)) {
      const day = Number(String(spesa.data).split('-')[2]);
      if (!Number.isInteger(day) || day < 1) continue;
      const row = wb.row(sheetDoc, day - 1 + FIRST_DAY_ROW);

      if (spesa.destinazione != null) wb.appendUniqueText(row, COL_DESTINAZIONE, spesa.destinazione, '\\');
      if (spesa.scopo != null) wb.appendUniqueText(row, COL_SCOPO, spesa.scopo, '\\');
      if (spesa.note != null) wb.appendUniqueText(row, COL_NOTE, spesa.note, '; ');

      const colIdx = CATEGORY_COLUMN_MAP[spesa.categoria];
      if (colIdx == null || !(spesa.importo > 0)) continue;
      const cell = wb.cell(row, colIdx);
      const impVal = formatAmount(spesa.importo);
      const fillColor = wb.fillColorOf(cell);
      const isAlreadyHighlighted = fillColor === GOLD || fillColor === YELLOW;
      const isForeign = Boolean(spesa.valuta_straniera);
      const type = wb.cellType(cell);
      const existingFormula = type === 'NUMERIC'
        ? formatAmount(Number(textOf(child(cell, 'v'))))
        : type === 'FORMULA' ? textOf(child(cell, 'f')) : null;

      if (existingFormula == null || wb.cellText(cell) === '') {
        // Prima spesa del giorno per questa categoria
        wb.setNumber(cell, Number(impVal));
        if (isForeign) wb.highlight(cell, GOLD);
      } else {
        // Più spese nello stesso giorno: restano visibili come formula di somma
        wb.setFormula(cell, `${existingFormula}+${impVal}`);
        if (isForeign || isAlreadyHighlighted) wb.highlight(cell, YELLOW);
      }
    }
  }
  return wb.save();
}

/** Nomi dei fogli del modello (per verificare che sia quello giusto). */
export async function templateSheetNames(bytes) {
  const wb = await Workbook.load(bytes);
  return Object.keys(wb.sheetPaths);
}
