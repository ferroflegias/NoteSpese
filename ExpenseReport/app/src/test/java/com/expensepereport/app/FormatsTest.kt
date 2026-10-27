package com.expensepereport.app

import com.expensepereport.app.util.ImageUtils
import com.expensepereport.app.util.formatAmount
import com.expensepereport.app.util.isValidIsoDate
import com.expensepereport.app.util.parseAmount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatsTest {

    @Test
    fun isValidIsoDate_acceptsRealDates() {
        assertTrue(isValidIsoDate("2026-03-31"))
        assertTrue(isValidIsoDate("2024-02-29"))
    }

    @Test
    fun isValidIsoDate_rejectsImpossibleOrMalformedDates() {
        assertFalse(isValidIsoDate("2026-02-30"))
        assertFalse(isValidIsoDate("2025-02-29"))
        assertFalse(isValidIsoDate("2026-13-01"))
        assertFalse(isValidIsoDate("31/03/2026"))
        assertFalse(isValidIsoDate("2026-3-1"))
        assertFalse(isValidIsoDate(""))
    }

    @Test
    fun parseAmount_acceptsCommaAndDot() {
        assertEquals(12.5, parseAmount("12,50")!!, 0.001)
        assertEquals(12.5, parseAmount(" 12.50 ")!!, 0.001)
        assertNull(parseAmount("abc"))
    }

    @Test
    fun formatAmount_usesTwoDecimalsWithDot() {
        assertEquals("45.50", formatAmount(45.5))
    }

    @Test
    fun calculateInSampleSize_keepsLongestSideWithinLimit() {
        assertEquals(1, ImageUtils.calculateInSampleSize(4000, 3000, 4096))
        assertEquals(2, ImageUtils.calculateInSampleSize(8000, 6000, 4096))
        assertEquals(4, ImageUtils.calculateInSampleSize(3000, 12000, 4096))
    }
}
