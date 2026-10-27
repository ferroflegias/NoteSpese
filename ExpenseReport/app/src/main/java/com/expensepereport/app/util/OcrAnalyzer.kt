package com.expensepereport.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.regex.Pattern
import kotlin.coroutines.resume

data class OcrResult(
    val dataStr: String,
    val destinazione: String,
    val importo: Double,
    val categoriaSuggerita: String,
    val pagamentoSuggerito: String,
    val noteExtracted: String
)

object OcrAnalyzer {

    suspend fun analyzeReceipt(context: Context, uri: Uri, isPdf: Boolean = false): OcrResult? {
        val bitmap = if (isPdf) {
            renderPdfFirstPageToBitmap(context, uri)
        } else {
            ImageUtils.loadOrientedBitmap(context, uri) ?: renderPdfFirstPageToBitmap(context, uri)
        } ?: return null

        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        val visionText: Text? = try {
            suspendCancellableCoroutine { continuation ->
                recognizer.process(inputImage)
                    .addOnSuccessListener { result -> continuation.resume(result) }
                    .addOnFailureListener { continuation.resume(null) }
            }
        } finally {
            recognizer.close()
        }

        if (visionText == null) return null

        val fullText = visionText.text
        if (fullText.isBlank()) return null

        // 1. Date extraction (DD/MM/YYYY, DD-MM-YYYY, DD.MM.YYYY)
        var extractedDateStr = todayIsoDate()

        val datePattern = Pattern.compile("\\b(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{2,4})\\b")
        val dateMatcher = datePattern.matcher(fullText)
        while (dateMatcher.find()) {
            val d = dateMatcher.group(1)?.toIntOrNull() ?: continue
            val m = dateMatcher.group(2)?.toIntOrNull() ?: continue
            var y = dateMatcher.group(3)?.toIntOrNull() ?: continue
            if (y < 100) y += 2000
            // Strict validation: reject impossible dates (e.g. 31/02) instead of letting them roll over
            val candidate = String.format(Locale.US, "%04d-%02d-%02d", y, m, d)
            if (isValidIsoDate(candidate)) {
                extractedDateStr = candidate
                break
            }
        }

        // 2. Amount extraction
        val lines = fullText.split("\n")
        val totalCandidates = mutableListOf<Double>()
        val allAmounts = mutableListOf<Double>()

        val amountPattern = Pattern.compile("\\b\\d+[,.]\\d{2}\\b")
        val amountMatcher = amountPattern.matcher(fullText)
        while (amountMatcher.find()) {
            val amtStr = amountMatcher.group().replace(",", ".")
            amtStr.toDoubleOrNull()?.let { allAmounts.add(it) }
        }

        val keywords = listOf("totale", "tot", "eur", "€", "importo", "somma", "euro")
        for (line in lines) {
            val lowerLine = line.lowercase(Locale.ITALIAN)
            if (keywords.any { lowerLine.contains(it) }) {
                val lineMatcher = amountPattern.matcher(line)
                while (lineMatcher.find()) {
                    val amtStr = lineMatcher.group().replace(",", ".")
                    amtStr.toDoubleOrNull()?.let { totalCandidates.add(it) }
                }
            }
        }

        val extractedAmount = when {
            totalCandidates.isNotEmpty() -> totalCandidates.maxOrNull() ?: 0.0
            allAmounts.isNotEmpty() -> allAmounts.maxOrNull() ?: 0.0
            else -> 0.0
        }

        // 3. Destination / Merchant (first line with valid text)
        var extractedDestinazione = "Esercente Sconosciuto"
        val validLines = lines.map { it.trim() }.filter { it.length > 3 }
        for (vl in validLines.take(4)) {
            val lower = vl.lowercase(Locale.ITALIAN)
            // "tel" must be a whole word, otherwise names like "Hotel ..." are discarded
            if (!lower.contains("ricevuta") && !lower.contains("scontrino") && !lower.contains("fattura") && !lower.contains("via ") && !TEL_REGEX.containsMatchIn(lower)) {
                extractedDestinazione = vl.take(40)
                break
            }
        }

        // 4. Category & Payment suggestion
        val textLower = fullText.lowercase(Locale.ITALIAN)
        var categoriaSuggerita = "Altro"
        var pagamentoSuggerito = "Contanti"

        if (listOf("benzina", "gasolio", "q8", "eni", "agip", "tamoil", "shell", "carburante", "fuel", "distributore").any { textLower.contains(it) }) {
            categoriaSuggerita = "Carburante"
            pagamentoSuggerito = "Carta Carburante"
        } else if (listOf("ristorante", "pizzeria", "bar", "trattoria", "osteria", "caffe", "coffee", "food", "pranzo", "cena", "ristoro").any { textLower.contains(it) }) {
            categoriaSuggerita = "Bar/Rist/Alb"
            pagamentoSuggerito = "CC"
        } else if (listOf("parcheggio", "sosta", "parking", "garage").any { textLower.contains(it) }) {
            categoriaSuggerita = "Parcheggio/Taxi"
            pagamentoSuggerito = "Contanti"
        } else if (listOf("telepass", "autostrade", "pedaggio").any { textLower.contains(it) }) {
            categoriaSuggerita = "Telepass"
        }

        if (listOf("carta", "bancomat", "pos", "visa", "mastercard", "contactless", "pagobancomat").any { textLower.contains(it) }) {
            pagamentoSuggerito = "CC"
        }

        val noteExtracted = fullText.replace("\n", " ").take(120)

        return OcrResult(
            dataStr = extractedDateStr,
            destinazione = extractedDestinazione,
            importo = extractedAmount,
            categoriaSuggerita = categoriaSuggerita,
            pagamentoSuggerito = pagamentoSuggerito,
            noteExtracted = noteExtracted
        )
    }

    private val TEL_REGEX = Regex("\\btel\\b")

    private fun renderPdfFirstPageToBitmap(context: Context, pdfUri: Uri): Bitmap? {
        return try {
            context.contentResolver.openFileDescriptor(pdfUri, "r")?.use { pfd ->
                PdfRenderer(pfd).use { pdfRenderer ->
                    if (pdfRenderer.pageCount == 0) return null
                    pdfRenderer.openPage(0).use { page ->
                        val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
