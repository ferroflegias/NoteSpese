// Categorie e metodi di pagamento: identici all'app Android (data/ExpenseCategories.kt),
// perché le chiavi salvate nel database corrispondono alle colonne del modello Excel.

export const RISTORANTI = '🍴🛌🏻🍺';
export const PARCHEGGI = '🅿️🚕✈️🚅';
export const CARBURANTE = '⛽';
export const TELEPASS = '🛣️';
export const NOLO = 'Nolo 🚗';
export const ALTRO = 'Altro';

export const PAYMENT_CC = '💳';
export const PAYMENT_CASH = '💰';
export const PAYMENT_FUEL_CARD = '💳⛽';

export const CATEGORY_OPTIONS = [RISTORANTI, PARCHEGGI, CARBURANTE, TELEPASS, NOLO, ALTRO];
export const PAYMENT_OPTIONS = [PAYMENT_CC, PAYMENT_CASH, PAYMENT_FUEL_CARD];

export const LABEL_CC = 'CC (Carta)';
export const LABEL_CC_LEGACY = 'CC';
export const LABEL_CASH = 'Contanti';
export const LABEL_FUEL_CARD = 'Carta Carburante';

export const KEY_TELEPASS = 'TELEPASS';

/** Chip della categoria suggerita dall'OCR. */
export function categoryFromOcr(suggestion) {
  switch (suggestion) {
    case 'Bar/Rist/Alb': return RISTORANTI;
    case 'Parcheggio/Taxi': return PARCHEGGI;
    case 'Carburante': return CARBURANTE;
    case 'Telepass': return TELEPASS;
    case 'Nolo': return NOLO;
    default: return ALTRO;
  }
}

export function paymentFromOcr(suggestion) {
  if (suggestion === 'Contanti') return PAYMENT_CASH;
  if (suggestion === 'Carta Carburante') return PAYMENT_FUEL_CARD;
  return PAYMENT_CC;
}

/** Chiave della categoria salvata nel database. */
export function categoryKey(category, payment) {
  switch (category) {
    case RISTORANTI: return payment === PAYMENT_CC ? 'RISTORANTI_CC' : 'RISTORANTI_CONTANTI';
    case PARCHEGGI: return payment === PAYMENT_CC ? 'PARCHEGGI_CC' : 'PARCHEGGI_CONTANTI';
    case CARBURANTE: return payment === PAYMENT_FUEL_CARD ? 'CARBURANTE_CARTA' : 'CARBURANTE_CC';
    case TELEPASS: return KEY_TELEPASS;
    case NOLO: return 'NOLO';
    default: return 'ALTRO';
  }
}

/** Metodo di pagamento salvato nel database; il Telepass non ne ha. */
export function paymentLabel(category, payment) {
  if (category === TELEPASS) return null;
  switch (payment) {
    case PAYMENT_CC: return LABEL_CC;
    case PAYMENT_CASH: return LABEL_CASH;
    default: return LABEL_FUEL_CARD;
  }
}

/** Totali del mese come nella scheda Report dell'app Android. */
export function monthTotals(spese) {
  const sum = (predicate) => spese.filter(predicate).reduce((acc, s) => acc + (s.importo || 0), 0);
  const byCategory = new Map();
  for (const s of spese) {
    const entry = byCategory.get(s.categoria) ?? { total: 0, count: 0 };
    entry.total += s.importo || 0;
    entry.count += 1;
    byCategory.set(s.categoria, entry);
  }
  return {
    total: sum(() => true),
    cc: sum((s) => s.metodo_pagamento === LABEL_CC || s.metodo_pagamento === LABEL_CC_LEGACY),
    cash: sum((s) => s.metodo_pagamento === LABEL_CASH),
    fuelCard: sum((s) => s.metodo_pagamento === LABEL_FUEL_CARD),
    telepass: sum((s) => s.categoria === KEY_TELEPASS),
    byCategory
  };
}
