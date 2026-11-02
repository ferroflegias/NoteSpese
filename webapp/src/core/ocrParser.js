// Estrazione dei dati dal testo di uno scontrino: stessa logica dell'app Android
// (util/OcrAnalyzer.kt, parseReceiptText), coperta dagli stessi test.
import { isValidIsoDate, todayIsoDate } from './formats.js';

const FUEL_WORDS = ['benzina', 'gasolio', 'q8', 'eni', 'agip', 'tamoil', 'shell', 'carburante', 'fuel', 'distributore'];
const RESTAURANT_WORDS = ['ristorante', 'pizzeria', 'bar', 'trattoria', 'osteria', 'caffe', 'caffè', 'coffee', 'food', 'pranzo', 'cena', 'ristoro', 'hotel', 'albergo'];
const PARKING_WORDS = ['parcheggio', 'sosta', 'parking', 'garage', 'taxi'];
const TOLL_WORDS = ['telepass', 'autostrade', 'autostrada', 'pedaggio'];
const CARD_WORDS = ['carta', 'bancomat', 'pos', 'visa', 'mastercard', 'contactless', 'pagobancomat'];
const TOTAL_WORDS = ['totale', 'tot', 'eur', '€', 'importo', 'somma', 'euro'];
const MERCHANT_EXCLUDED_WORDS = ['ricevuta', 'scontrino', 'fattura', 'via', 'tel'];

const DATE_REGEX = /\b(\d{1,2})[/.-](\d{1,2})[/.-](\d{2,4})\b/g;
const AMOUNT_REGEX = /\b\d+[,.]\d{2}\b/g;

const escapeRegex = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/**
 * Ricerca senza distinzione di maiuscole della parola non preceduta da una lettera. Le parole
 * corte (fino a 4 caratteri, es. "eni", "bar", "pos", "tel") non devono neanche essere seguite
 * da una lettera: "beni", "barcode", "posto" o "Hotel" non corrispondono.
 */
function containsWord(text, word) {
  const end = word.length <= 4 ? '(?!\\p{L})' : '';
  return new RegExp(`(?<!\\p{L})${escapeRegex(word)}${end}`, 'iu').test(text);
}

const pad = (n, len = 2) => String(n).padStart(len, '0');

function amountsIn(text) {
  return [...text.matchAll(AMOUNT_REGEX)].map((m) => Number(m[0].replace(',', '.'))).filter((n) => !Number.isNaN(n));
}

/**
 * Data, esercente, importo, categoria e pagamento suggeriti dal testo dello scontrino;
 * null se il testo è vuoto.
 */
export function parseReceiptText(fullText, today = todayIsoDate()) {
  if (!fullText || !fullText.trim()) return null;
  const lines = fullText.split(/\r?\n/);

  // 1. Data: la prima data di calendario reale
  let dataStr = today;
  for (const m of fullText.matchAll(DATE_REGEX)) {
    let y = Number(m[3]);
    if (y < 100) y += 2000;
    const candidate = `${pad(y, 4)}-${pad(Number(m[2]))}-${pad(Number(m[1]))}`;
    if (y >= 2000 && y <= 2099 && isValidIsoDate(candidate)) {
      dataStr = candidate;
      break;
    }
  }

  // 2. Importo: il più alto su una riga "totale", altrimenti il più alto del testo
  const totalCandidates = lines.filter((line) => TOTAL_WORDS.some((w) => containsWord(line, w))).flatMap(amountsIn);
  const all = amountsIn(fullText);
  const importo = totalCandidates.length ? Math.max(...totalCandidates) : (all.length ? Math.max(...all) : 0);

  // 3. Esercente: la prima delle righe in alto che non è intestazione, indirizzo o telefono
  const destinazione = lines.map((l) => l.trim())
    .filter((l) => l.length > 3)
    .slice(0, 4)
    .find((l) => MERCHANT_EXCLUDED_WORDS.every((w) => !containsWord(l, w)))
    ?.slice(0, 40) ?? 'Esercente Sconosciuto';

  // 4. Categoria e pagamento suggeriti
  let categoriaSuggerita = 'Altro';
  let pagamentoSuggerito = 'Contanti';
  if (FUEL_WORDS.some((w) => containsWord(fullText, w))) {
    categoriaSuggerita = 'Carburante';
    pagamentoSuggerito = 'Carta Carburante';
  } else if (RESTAURANT_WORDS.some((w) => containsWord(fullText, w))) {
    categoriaSuggerita = 'Bar/Rist/Alb';
    pagamentoSuggerito = 'CC';
  } else if (PARKING_WORDS.some((w) => containsWord(fullText, w))) {
    categoriaSuggerita = 'Parcheggio/Taxi';
  } else if (TOLL_WORDS.some((w) => containsWord(fullText, w))) {
    categoriaSuggerita = 'Telepass';
  }
  if (CARD_WORDS.some((w) => containsWord(fullText, w))) pagamentoSuggerito = 'CC';

  return {
    dataStr,
    destinazione,
    importo,
    categoriaSuggerita,
    pagamentoSuggerito,
    noteExtracted: fullText.replace(/\n/g, ' ').slice(0, 120)
  };
}
