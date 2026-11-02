package com.expensepereport.app

import com.expensepereport.app.data.ExpenseCategories
import com.expensepereport.app.data.attachmentFolderParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategoriesTest {

    @Test
    fun categoryKey_matchesExcelColumns() {
        with(ExpenseCategories) {
            assertEquals("RISTORANTI_CC", categoryKey(RISTORANTI, PAYMENT_CC))
            assertEquals("RISTORANTI_CONTANTI", categoryKey(RISTORANTI, PAYMENT_CASH))
            assertEquals("PARCHEGGI_CONTANTI", categoryKey(PARCHEGGI, PAYMENT_CASH))
            assertEquals("CARBURANTE_CARTA", categoryKey(CARBURANTE, PAYMENT_FUEL_CARD))
            assertEquals("CARBURANTE_CC", categoryKey(CARBURANTE, PAYMENT_CC))
            assertEquals("TELEPASS", categoryKey(TELEPASS, PAYMENT_CASH))
            assertEquals("NOLO", categoryKey(NOLO, PAYMENT_CC))
            assertEquals("ALTRO", categoryKey(ALTRO, PAYMENT_CC))
        }
    }

    @Test
    fun paymentLabel_isNullForTelepass() {
        with(ExpenseCategories) {
            assertNull(paymentLabel(TELEPASS, PAYMENT_CC))
            assertEquals(LABEL_CC, paymentLabel(NOLO, PAYMENT_CC))
            assertEquals(LABEL_CASH, paymentLabel(RISTORANTI, PAYMENT_CASH))
            assertEquals(LABEL_FUEL_CARD, paymentLabel(CARBURANTE, PAYMENT_FUEL_CARD))
        }
    }

    @Test
    fun ocrSuggestions_mapToChips() {
        with(ExpenseCategories) {
            assertEquals(RISTORANTI, categoryFromOcr("Bar/Rist/Alb"))
            assertEquals(ALTRO, categoryFromOcr("sconosciuta"))
            assertEquals(PAYMENT_FUEL_CARD, paymentFromOcr("Carta Carburante"))
            assertEquals(PAYMENT_CC, paymentFromOcr("CC"))
        }
    }

    @Test
    fun attachmentFolderParts_splitsYearAndMonth() {
        assertEquals("2026" to "03", attachmentFolderParts("2026-03-31"))
    }
}
