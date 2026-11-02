package com.expensepereport.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.expensepereport.app.data.Spesa
import com.expensepereport.app.data.SpesaRepository
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

var lastExcelError: String? = null

object DocumentGenerator {

    val MONTH_NAMES = listOf(
        "Gennaio", "Febbraio", "Marzo", "Aprile", "Maggio", "Giugno",
        "Luglio", "Agosto", "Settembre", "Ottobre", "Novembre", "Dicembre"
    )

    private val CATEGORY_COLUMN_MAP = mapOf(
        "TELEPASS" to 5,              // Column F
        "NOLO" to 6,                  // Column G
        "PARCHEGGI_CONTANTI" to 7,    // Column H
        "PARCHEGGI_CC" to 8,          // Column I
        "RISTORANTI_CONTANTI" to 9,  // Column J
        "RISTORANTI_CC" to 10,        // Column K
        "CARBURANTE_CC" to 11,        // Column L
        "CARBURANTE_CARTA" to 12,     // Column M
        "ALTRO" to 13                 // Column N
    )

    private val PDF_MAGIC = "%PDF".toByteArray(Charsets.US_ASCII)

    private fun getCellText(cell: Cell?): String {
        if (cell == null) return ""
        return when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue.trim()
            CellType.NUMERIC -> {
                val num = cell.numericCellValue
                if (num == num.toLong().toDouble()) {
                    num.toLong().toString()
                } else {
                    formatAmount(num)
                }
            }
            CellType.BOOLEAN -> cell.booleanCellValue.toString()
            CellType.FORMULA -> {
                try {
                    cell.cellFormula.trim()
                } catch (e: Exception) {
                    ""
                }
            }
            else -> ""
        }
    }

    /** Writes [value] in the cell, appending it with [separator] if the cell already holds other values. */
    private fun appendUniqueText(row: Row, colIdx: Int, value: String, separator: String) {
        val cell = row.getCell(colIdx) ?: row.createCell(colIdx)
        val currVal = getCellText(cell)
        if (currVal.isEmpty()) {
            cell.setCellValue(value)
        } else if (!currVal.split(separator.trim()).map { it.trim() }.contains(value)) {
            cell.setCellValue("$currVal$separator$value")
        }
    }

    private fun highlightCell(workbook: XSSFWorkbook, cell: Cell, colorIndex: Short) {
        val newStyle = workbook.createCellStyle()
        cell.cellStyle?.let { newStyle.cloneStyleFrom(it) }
        newStyle.fillForegroundColor = colorIndex
        newStyle.fillPattern = FillPatternType.SOLID_FOREGROUND
        cell.cellStyle = newStyle
    }

    suspend fun generateExcel(
        context: Context,
        spesaRepository: SpesaRepository,
        templateFile: File,
        year: Int,
        mode: String, // "singolo", "range", "anno"
        startMonth: Int,
        endMonth: Int
    ): File? {
        lastExcelError = null
        if (!templateFile.exists()) {
            lastExcelError = "File modello non esistente in storage locale"
            return null
        }

        return try {
            val workbook = templateFile.inputStream().use { XSSFWorkbook(it) }

            val rowOffset = 4
            val orangeColorIndex = IndexedColors.GOLD.index
            val yellowColorIndex = IndexedColors.YELLOW.index

            for (m in startMonth..endMonth) {
                val sheet = workbook.getSheet(MONTH_NAMES[m - 1]) ?: continue

                for (spesa in spesaRepository.getSpeseForMonth(year, m)) {
                    val dateParts = spesa.data.split("-")
                    if (dateParts.size < 3) continue
                    val day = dateParts[2].toIntOrNull() ?: continue
                    val targetRowIdx = day + rowOffset
                    val row = sheet.getRow(targetRowIdx) ?: sheet.createRow(targetRowIdx)

                    spesa.destinazione?.let { appendUniqueText(row, 2, it, "\\") } // Column C
                    spesa.scopo?.let { appendUniqueText(row, 3, it, "\\") }        // Column D
                    spesa.note?.let { appendUniqueText(row, 14, it, "; ") }        // Column O

                    // Value in specific Category column
                    val colIdx = CATEGORY_COLUMN_MAP[spesa.categoria]
                    if (colIdx != null && spesa.importo > 0) {
                        val cell = row.getCell(colIdx) ?: row.createCell(colIdx)
                        val impVal = formatAmount(spesa.importo)

                        val currFillColor = cell.cellStyle?.fillForegroundColor ?: 0
                        val isAlreadyHighlighted = currFillColor == orangeColorIndex || currFillColor == yellowColorIndex
                        val isForeign = spesa.valutaStraniera == 1

                        val existingFormula = when {
                            cell.cellType == CellType.NUMERIC -> formatAmount(cell.numericCellValue)
                            cell.cellType == CellType.FORMULA -> cell.cellFormula
                            else -> null
                        }

                        if (existingFormula == null || getCellText(cell).isEmpty()) {
                            // Empty (or text) cell: first expense of the day for this category
                            cell.setCellValue(impVal.toDouble())
                            if (isForeign) highlightCell(workbook, cell, orangeColorIndex)
                        } else {
                            // Several expenses on the same day: keep them visible as a sum formula
                            cell.cellFormula = "$existingFormula+$impVal"
                            if (isForeign || isAlreadyHighlighted) highlightCell(workbook, cell, yellowColorIndex)
                        }
                    }
                }
            }

            // Template totals are formulas: make Excel recompute them when the file is opened
            workbook.setForceFormulaRecalculation(true)

            val filename = when (mode) {
                "anno" -> "Note_Spese_Anno_${year}.xlsx"
                "range" -> "Note_Spese_${MONTH_NAMES[startMonth - 1]}_${MONTH_NAMES[endMonth - 1]}_${year}.xlsx"
                else -> "Note_Spese_${MONTH_NAMES[startMonth - 1]}_${year}.xlsx"
            }

            val outFile = File(context.cacheDir, filename)
            workbook.use { wb ->
                FileOutputStream(outFile).use { wb.write(it) }
            }
            outFile
        } catch (t: Throwable) {
            t.printStackTrace()
            lastExcelError = "${t.javaClass.simpleName}: ${t.message}"
            null
        }
    }

    /**
     * Builds a PDF with up to 4 attachments per page; PDF attachments are appended at the end.
     * [readAttachment] loads an attachment (local file or Google Drive) from its stored path.
     */
    suspend fun generatePdfAttachments(
        context: Context,
        spesaRepository: SpesaRepository,
        year: Int,
        month: Int,
        readAttachment: suspend (String) -> ByteArray?
    ): File? {
        val speseWithAttachment = try {
            spesaRepository.getSpeseForMonth(year, month).filter { !it.allegatoPath.isNullOrBlank() }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
        if (speseWithAttachment.isEmpty()) return null

        val pdfDocument = PdfDocument()
        return try {
            val pageWidth = 595
            val pageHeight = 842

            var pageNumber = 1
            var page = pdfDocument.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            var canvas = page.canvas

            val titlePaint = Paint().apply {
                color = android.graphics.Color.BLACK
                textSize = 16f
                isFakeBoldText = true
                isAntiAlias = true
            }

            val bodyPaint = Paint().apply {
                color = android.graphics.Color.BLACK
                textSize = 9f
                isAntiAlias = true
            }

            val boldPaint = Paint().apply {
                color = android.graphics.Color.BLACK
                textSize = 10f
                isFakeBoldText = true
                isAntiAlias = true
            }

            // Draws bitmaps directly in grayscale, without allocating a converted copy
            val grayscaleBitmapPaint = Paint().apply {
                isAntiAlias = true
                isFilterBitmap = true
                isDither = true
                colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            }

            canvas.drawText("Allegati Spese (${speseWithAttachment.size} Voci) - ${MONTH_NAMES[month - 1]} $year", 30f, 40f, titlePaint)

            val pendingPdfsToMerge = mutableListOf<Pair<Spesa, ByteArray>>()

            val colPositions = floatArrayOf(30f, 305f)
            val rowPositions = floatArrayOf(70f, 440f)

            val maxCellWidth = 260f
            val maxCellHeight = 290f

            for ((index, spesa) in speseWithAttachment.withIndex()) {
                val pageItemIndex = index % 4

                if (index > 0 && pageItemIndex == 0) {
                    pdfDocument.finishPage(page)
                    pageNumber++
                    page = pdfDocument.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
                    canvas = page.canvas
                }

                val startX = colPositions[pageItemIndex % 2]
                val startY = rowPositions[pageItemIndex / 2]

                canvas.drawText("ID #${spesa.id ?: "-"} | Data: ${spesa.data}", startX, startY + 12f, boldPaint)
                canvas.drawText("Importo: € ${formatAmount(spesa.importo)}", startX, startY + 26f, boldPaint)
                canvas.drawText("Destinazione: ${spesa.destinazione ?: "-"}", startX, startY + 40f, bodyPaint)
                canvas.drawText("Cat: ${spesa.categoria} | Pag: ${spesa.metodoPagamento ?: "Standard"}", startX, startY + 54f, bodyPaint)

                val attachmentBytes = readAttachment(spesa.allegatoPath!!)
                if (attachmentBytes == null) {
                    canvas.drawText("[Allegato non disponibile]", startX, startY + 80f, bodyPaint)
                    continue
                }

                // Drive links carry no file extension: recognise PDFs from their content
                if (isPdf(attachmentBytes)) {
                    pendingPdfsToMerge.add(Pair(spesa, attachmentBytes))
                    canvas.drawText("📄 [Documento PDF allegato e unito in coda]", startX, startY + 80f, boldPaint)
                    continue
                }

                try {
                    val bitmap = decodeSampledBitmap(attachmentBytes, maxCellWidth, maxCellHeight)
                    if (bitmap != null) {
                        val imageTop = startY + 65f
                        drawBitmapFitted(canvas, bitmap, startX, imageTop, maxCellWidth, maxCellHeight, grayscaleBitmapPaint)
                        bitmap.recycle()
                    } else {
                        canvas.drawText("[Immagine non visualizzabile]", startX, startY + 80f, bodyPaint)
                    }
                } catch (e: Exception) {
                    canvas.drawText("[Errore caricamento immagine]", startX, startY + 80f, bodyPaint)
                }
            }

            pdfDocument.finishPage(page)

            for ((spesa, pdfBytes) in pendingPdfsToMerge) {
                val tempPdfFile = File(context.cacheDir, "temp_attachment_${spesa.id}.pdf")
                try {
                    tempPdfFile.writeBytes(pdfBytes)

                    ParcelFileDescriptor.open(tempPdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { fileDescriptor ->
                        PdfRenderer(fileDescriptor).use { pdfRenderer ->
                            for (i in 0 until pdfRenderer.pageCount) {
                                pdfRenderer.openPage(i).use { pdfPage ->
                                    pageNumber++
                                    page = pdfDocument.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
                                    canvas = page.canvas

                                    canvas.drawText("Allegato PDF in Coda - ID #${spesa.id ?: "-"} (Pagina ${i + 1}/${pdfRenderer.pageCount})", 30f, 30f, boldPaint)

                                    val scaleFactor = 4f
                                    val highResBitmap = Bitmap.createBitmap(
                                        (pdfPage.width * scaleFactor).toInt(),
                                        (pdfPage.height * scaleFactor).toInt(),
                                        Bitmap.Config.ARGB_8888
                                    )
                                    // PDF pages may be transparent: paint a white background first
                                    highResBitmap.eraseColor(android.graphics.Color.WHITE)
                                    pdfPage.render(highResBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                                    drawBitmapFitted(canvas, highResBitmap, 30f, 50f, pageWidth - 60f, pageHeight - 80f, grayscaleBitmapPaint)
                                    highResBitmap.recycle()

                                    pdfDocument.finishPage(page)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    tempPdfFile.delete()
                }
            }

            val pdfFile = File(context.cacheDir, "Allegati_Spese_${MONTH_NAMES[month - 1]}_${year}.pdf")
            FileOutputStream(pdfFile).use { pdfDocument.writeTo(it) }
            pdfFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            pdfDocument.close()
        }
    }

    private fun isPdf(bytes: ByteArray): Boolean =
        bytes.size >= PDF_MAGIC.size && bytes.copyOfRange(0, PDF_MAGIC.size).contentEquals(PDF_MAGIC)

    /**
     * Decodes an image at a resolution sufficient for the target box (at ~4x for print quality),
     * instead of loading multi-megapixel photos at full size.
     */
    private fun decodeSampledBitmap(bytes: ByteArray, targetWidth: Float, targetHeight: Float): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val maxDimension = (maxOf(targetWidth, targetHeight) * 4).toInt()
        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageUtils.calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /** Draws [bitmap] scaled to fit inside the given box, keeping its aspect ratio. */
    private fun drawBitmapFitted(
        canvas: Canvas,
        bitmap: Bitmap,
        left: Float,
        top: Float,
        maxWidth: Float,
        maxHeight: Float,
        paint: Paint
    ) {
        val ratio = minOf(maxWidth / bitmap.width, maxHeight / bitmap.height)
        val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
        val dstRect = RectF(left, top, left + bitmap.width * ratio, top + bitmap.height * ratio)
        canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
    }
}
