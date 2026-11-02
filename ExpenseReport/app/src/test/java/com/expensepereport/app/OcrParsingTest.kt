package com.expensepereport.app

import com.expensepereport.app.util.OcrAnalyzer
import com.expensepereport.app.util.todayIsoDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OcrParsingTest {

    private fun parse(text: String) = OcrAnalyzer.parseReceiptText(text).also { assertNotNull(it) }!!

    @Test
    fun blankText_returnsNull() {
        assertNull(OcrAnalyzer.parseReceiptText("  \n "))
    }

    @Test
    fun restaurantReceipt() {
        val result = parse(
            """
            Ristorante Da Mario
            Via Roma 1 - Milano
            Tel 02 123456
            31/03/2026 20:15
            Coperto 2,00
            TOTALE EURO 45,50
            Pagamento con carta
            """.trimIndent()
        )
        assertEquals("2026-03-31", result.dataStr)
        assertEquals("Ristorante Da Mario", result.destinazione)
        assertEquals(45.50, result.importo, 0.001)
        assertEquals("Bar/Rist/Alb", result.categoriaSuggerita)
        assertEquals("CC", result.pagamentoSuggerito)
    }

    @Test
    fun impossibleDatesAreSkipped() {
        assertEquals("2026-01-15", parse("Scontrino\n31/02/2026\n15.01.26\nTotale 3,00").dataStr)
        assertEquals(todayIsoDate(), parse("Codice 99/99/9999\nTotale 3,00").dataStr)
    }

    @Test
    fun amountOnTotalLineWinsOverLargerNumbers() {
        val result = parse("Bar Centrale\nCodice 123,45\nTotale 7,80\nResto 2,20")
        assertEquals(7.80, result.importo, 0.001)
    }

    @Test
    fun amountWithEuroSignAttached() {
        // Without the "€" match the largest number (99,99) would be chosen
        assertEquals(12.50, parse("Esercente\nCodice 99,99\nPagato 12,50€").importo, 0.001)
    }

    @Test
    fun keywordsMatchWholeWordsOnly() {
        // "beni" (eni), "posto" (pos) and "barcode" (bar) must not trigger a suggestion
        val result = parse("Negozio Alfa\nbeni vari, posto 3, barcode 1234\nTotale 9,90")
        assertEquals("Altro", result.categoriaSuggerita)
        assertEquals("Contanti", result.pagamentoSuggerito)
    }

    @Test
    fun fuelReceipt() {
        val result = parse("ENI Station\nGasolio 40,00\nTotale 40,00")
        assertEquals("Carburante", result.categoriaSuggerita)
        assertEquals("Carta Carburante", result.pagamentoSuggerito)
    }

    @Test
    fun merchantIsNotMistakenForPhoneLine() {
        // "Hotel" contains "tel" and "Bellavista" contains "via"
        assertEquals("Hotel Bellavista", parse("Hotel Bellavista\nTotale 80,00").destinazione)
        assertEquals("Pasticceria Rossi", parse("Tel 0123 456789\nPasticceria Rossi\nTotale 2,00").destinazione)
    }

    @Test
    fun accentedKeywordsAreCaseInsensitive() {
        assertEquals("Bar/Rist/Alb", parse("Pasticceria Rossi\nCAFFÈ 1,20\nTotale 1,20").categoriaSuggerita)
    }
}
