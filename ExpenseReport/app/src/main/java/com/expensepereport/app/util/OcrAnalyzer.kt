package com.expensepereport.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OcrResult(
    val dataStr: String,
    val destinazione: String,
    val importo: Double,
    val categoriaSuggerita: String,
    val pagamentoSuggerito: String,
    val noteExtracted: String
)

/** OCR failure with a message suitable for the user. */
class OcrException(message: String) : Exception(message)

object OcrAnalyzer {

    private const val OCR_TIMEOUT_MS = 30_000L

    /**
     * Returns the data extracted from the receipt, or null if no text was found.
     * Throws [OcrException] when the image cannot be read or text recognition fails.
     */
    suspend fun analyzeReceipt(context: Context, uri: Uri, isPdf: Boolean = false): OcrResult? {
        val bitmap = if (isPdf) {
            renderPdfFirstPageToBitmap(context, uri)
        } else {
            ImageUtils.loadOrientedBitmap(context, uri, ImageUtils.PREVIEW_MAX_DIMENSION)
                ?: renderPdfFirstPageToBitmap(context, uri)
        } ?: throw OcrException("impossibile leggere l'immagine selezionata")

        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        val visionText: Text = try {
            withTimeoutOrNull(OCR_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    recognizer.process(inputImage)
                        .addOnSuccessListener { result -> if (continuation.isActive) continuation.resume(result) }
                        .addOnFailureListener { e ->
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    OcrException("riconoscimento del testo non riuscito (${e.localizedMessage ?: e.javaClass.simpleName})")
                                )
                            }
                        }
                        .addOnCanceledListener {
                            if (continuation.isActive) continuation.resumeWithException(OcrException("riconoscimento annullato"))
                        }
                }
            } ?: throw OcrException("il riconoscimento del testo non ha risposto entro ${OCR_TIMEOUT_MS / 1000} secondi")
        } finally {
            recognizer.close()
            bitmap.recycle()
        }

        return parseReceiptText(visionText.text)
    }

    /** Keywords searched in the receipt text with [containsWord]. */
    private val FUEL_WORDS = listOf("benzina", "gasolio", "q8", "eni", "agip", "tamoil", "shell", "carburante", "fuel", "distributore")
    private val RESTAURANT_WORDS = listOf("ristorante", "pizzeria", "bar", "trattoria", "osteria", "caffe", "caffè", "coffee", "food", "pranzo", "cena", "ristoro", "hotel", "albergo")
    private val PARKING_WORDS = listOf("parcheggio", "sosta", "parking", "garage", "taxi")
    private val TOLL_WORDS = listOf("telepass", "autostrade", "autostrada", "pedaggio")
    private val CARD_WORDS = listOf("carta", "bancomat", "pos", "visa", "mastercard", "contactless", "pagobancomat")
    private val TOTAL_WORDS = listOf("totale", "tot", "eur", "€", "importo", "somma", "euro")
    private val MERCHANT_EXCLUDED_WORDS = listOf("ricevuta", "scontrino", "fattura", "via", "tel")

    private val DATE_REGEX = Regex("""\b(\d{1,2})[/.-](\d{1,2})[/.-](\d{2,4})\b""")
    private val AMOUNT_REGEX = Regex("""\b\d+[,.]\d{2}\b""")

    /**
     * Extracts date, merchant, amount, category and payment method from the text of a receipt.
     * Returns null when the text is blank. Pure function: it is covered by unit tests.
     */
    fun parseReceiptText(fullText: String): OcrResult? {
        if (fullText.isBlank()) return null
        val lines = fullText.lines()

        // 1. Date (DD/MM/YYYY, DD-MM-YYYY, DD.MM.YY...): the first real calendar date
        val extractedDate = DATE_REGEX.findAll(fullText).firstNotNullOfOrNull { match ->
            val (d, m, yRaw) = match.destructured
            var y = yRaw.toInt()
            if (y < 100) y += 2000
            // Strict validation: reject impossible dates (e.g. 31/02) instead of letting them roll over
            String.format(Locale.US, "%04d-%02d-%02d", y, m.toInt(), d.toInt())
                .takeIf { y in 2000..2099 && isValidIsoDate(it) }
        } ?: todayIsoDate()

        // 2. Amount: the largest amount on a "total" line, otherwise the largest amount in the text
        fun amountsIn(text: String) = AMOUNT_REGEX.findAll(text).mapNotNull { it.value.replace(",", ".").toDoubleOrNull() }
        val totalCandidates = lines
            .filter { line -> TOTAL_WORDS.any { line.containsWord(it) } }
            .flatMap { amountsIn(it) }
        val extractedAmount = totalCandidates.maxOrNull() ?: amountsIn(fullText).maxOrNull() ?: 0.0

        // 3. Merchant: the first of the top lines that is not a header, an address or a phone number
        val extractedDestinazione = lines.map { it.trim() }
            .filter { it.length > 3 }
            .take(4)
            .firstOrNull { line -> MERCHANT_EXCLUDED_WORDS.none { line.containsWord(it) } }
            ?.take(40)
            ?: "Esercente Sconosciuto"

        // 4. Category & payment suggestion
        var categoriaSuggerita = "Altro"
        var pagamentoSuggerito = "Contanti"
        when {
            FUEL_WORDS.any { fullText.containsWord(it) } -> {
                categoriaSuggerita = "Carburante"
                pagamentoSuggerito = "Carta Carburante"
            }
            RESTAURANT_WORDS.any { fullText.containsWord(it) } -> {
                categoriaSuggerita = "Bar/Rist/Alb"
                pagamentoSuggerito = "CC"
            }
            PARKING_WORDS.any { fullText.containsWord(it) } -> categoriaSuggerita = "Parcheggio/Taxi"
            TOLL_WORDS.any { fullText.containsWord(it) } -> categoriaSuggerita = "Telepass"
        }
        if (CARD_WORDS.any { fullText.containsWord(it) }) {
            pagamentoSuggerito = "CC"
        }

        return OcrResult(
            dataStr = extractedDate,
            destinazione = extractedDestinazione,
            importo = extractedAmount,
            categoriaSuggerita = categoriaSuggerita,
            pagamentoSuggerito = pagamentoSuggerito,
            noteExtracted = fullText.replace("\n", " ").take(120)
        )
    }

    /**
     * Case-insensitive search of [word] not preceded by a letter. Short words (up to 4 characters,
     * e.g. "eni", "bar", "pos", "tel") must not be followed by a letter either, so "beni",
     * "barcode", "posto" or "Hotel" do not match; longer words also match at the beginning
     * of a longer word ("ristorante" in "ristoranteria"). Digits are allowed around words: "12,50EUR".
     */
    private fun String.containsWord(word: String): Boolean {
        val end = if (word.length <= 4) "(?!\\p{L})" else ""
        // (?iu): case-insensitive also for accented letters ("CAFFÈ")
        return Regex("(?iu)(?<!\\p{L})" + Regex.escape(word) + end).containsMatchIn(this)
    }

    private fun renderPdfFirstPageToBitmap(context: Context, pdfUri: Uri): Bitmap? {
        return try {
            context.contentResolver.openFileDescriptor(pdfUri, "r")?.use { pfd ->
                PdfRenderer(pfd).use { pdfRenderer ->
                    if (pdfRenderer.pageCount == 0) return null
                    pdfRenderer.openPage(0).use { page ->
                        val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                        // PDF pages are often transparent: without a white background the text
                        // would be black on black and the OCR would find nothing
                        bitmap.eraseColor(Color.WHITE)
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
