package com.expensepereport.app.util

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ISO_DATE_REGEX = Regex("\\d{4}-\\d{2}-\\d{2}")

fun todayIsoDate(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

/** True only for real calendar dates in the yyyy-MM-dd format used by the database. */
fun isValidIsoDate(value: String): Boolean {
    if (!ISO_DATE_REGEX.matches(value)) return false
    return try {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }.parse(value)
        true
    } catch (e: ParseException) {
        false
    }
}

/** Parses amounts typed with either comma or dot as decimal separator. */
fun parseAmount(value: String): Double? = value.trim().replace(",", ".").toDoubleOrNull()

fun formatAmount(value: Double): String = String.format(Locale.US, "%.2f", value)
