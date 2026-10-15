package com.expensepereport.app

import com.expensepereport.app.util.OcrResult
import org.junit.Assert.assertEquals
import org.junit.Test

class OcrDataTest {

    @Test
    fun testOcrResultStructure() {
        val ocrResult = OcrResult(
            dataStr = "2026-03-31",
            destinazione = "Ristorante Da Mario",
            importo = 45.50,
            categoriaSuggerita = "Bar/Rist/Alb",
            pagamentoSuggerito = "CC",
            noteExtracted = "Ristorante Da Mario - Totale 45.50"
        )

        assertEquals("2026-03-31", ocrResult.dataStr)
        assertEquals("Ristorante Da Mario", ocrResult.destinazione)
        assertEquals(45.50, ocrResult.importo, 0.001)
        assertEquals("Bar/Rist/Alb", ocrResult.categoriaSuggerita)
        assertEquals("CC", ocrResult.pagamentoSuggerito)
    }
}
