package com.expensepereport.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*
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

    suspend fun analyzeReceipt(context: Context, imageUri: Uri): OcrResult? {
        val bitmap = loadBitmapFromUri(context, imageUri) ?: return null
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        val visionText: Text? = suspendCancellableCoroutine { continuation ->
            recognizer.process(inputImage)
                .addOnSuccessListener { result ->
                    continuation.resume(result)
                }
                .addOnFailureListener {
                    continuation.resume(null)
                }
        }

        if (visionText == null) return null

        val fullText = visionText.text
        if (fullText.isBlank()) return null

        // 1. Date extraction (DD/MM/YYYY, DD-MM-YYYY, etc.)
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        var extractedDateStr = todayStr

        val datePattern = Pattern.compile("\\b(\\d{1,2})[/-](\\d{1,2})[/-](\\d{2,4})\\b")
        val dateMatcher = datePattern.matcher(fullText)
        if (dateMatcher.find()) {
            val d = dateMatcher.group(1)?.toIntOrNull() ?: 1
            val m = dateMatcher.group(2)?.toIntOrNull() ?: 1
            var y = dateMatcher.group(3)?.toIntOrNull() ?: 2026
            if (y < 100) y += 2000
            try {
                val calendar = Calendar.getInstance()
                calendar.set(y, m - 1, d)
                extractedDateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.time)
            } catch (e: Exception) {
                // Keep today's date if parse fails
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
            if (!lower.contains("ricevuta") && !lower.contains("scontrino") && !lower.contains("fattura") && !lower.contains("via ") && !lower.contains("tel")) {
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

    private fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            BitmapFactory.decodeStream(inputStream)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
