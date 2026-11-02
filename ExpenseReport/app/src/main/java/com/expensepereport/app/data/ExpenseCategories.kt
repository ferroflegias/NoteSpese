package com.expensepereport.app.data

/**
 * Categories and payment methods: the emoji chips shown when entering an expense, the keys
 * stored in the database (matching the columns of the Excel template) and the payment labels.
 */
object ExpenseCategories {

    // Category chips
    const val RISTORANTI = "🍴🛌🏻🍺"
    const val PARCHEGGI = "🅿️🚕✈️🚅"
    const val CARBURANTE = "⛽"
    const val TELEPASS = "🛣️"
    const val NOLO = "Nolo 🚗"
    const val ALTRO = "Altro"

    // Payment chips
    const val PAYMENT_CC = "💳"
    const val PAYMENT_CASH = "💰"
    const val PAYMENT_FUEL_CARD = "💳⛽"

    val CATEGORY_OPTIONS = listOf(RISTORANTI, PARCHEGGI, CARBURANTE, TELEPASS, NOLO, ALTRO)
    val PAYMENT_OPTIONS = listOf(PAYMENT_CC, PAYMENT_CASH, PAYMENT_FUEL_CARD)

    // Payment labels stored in the database ("CC" was used by the old web app)
    const val LABEL_CC = "CC (Carta)"
    const val LABEL_CC_LEGACY = "CC"
    const val LABEL_CASH = "Contanti"
    const val LABEL_FUEL_CARD = "Carta Carburante"

    /** Database category key of the Telepass expenses. */
    const val KEY_TELEPASS = "TELEPASS"

    /** Maps the category suggested by the OCR to the chip shown in the UI. */
    fun categoryFromOcr(suggestion: String): String = when (suggestion) {
        "Bar/Rist/Alb" -> RISTORANTI
        "Parcheggio/Taxi" -> PARCHEGGI
        "Carburante" -> CARBURANTE
        "Telepass" -> TELEPASS
        "Nolo" -> NOLO
        else -> ALTRO
    }

    fun paymentFromOcr(suggestion: String): String = when (suggestion) {
        "Contanti" -> PAYMENT_CASH
        "Carta Carburante" -> PAYMENT_FUEL_CARD
        else -> PAYMENT_CC
    }

    /** Database category key, matching the columns of the Excel template. */
    fun categoryKey(category: String, payment: String): String = when (category) {
        RISTORANTI -> if (payment == PAYMENT_CC) "RISTORANTI_CC" else "RISTORANTI_CONTANTI"
        PARCHEGGI -> if (payment == PAYMENT_CC) "PARCHEGGI_CC" else "PARCHEGGI_CONTANTI"
        CARBURANTE -> if (payment == PAYMENT_FUEL_CARD) "CARBURANTE_CARTA" else "CARBURANTE_CC"
        TELEPASS -> KEY_TELEPASS
        NOLO -> "NOLO"
        else -> "ALTRO"
    }

    /** Payment label stored in the database; Telepass has no payment method. */
    fun paymentLabel(category: String, payment: String): String? =
        if (category == TELEPASS) null else when (payment) {
            PAYMENT_CC -> LABEL_CC
            PAYMENT_CASH -> LABEL_CASH
            else -> LABEL_FUEL_CARD
        }
}
