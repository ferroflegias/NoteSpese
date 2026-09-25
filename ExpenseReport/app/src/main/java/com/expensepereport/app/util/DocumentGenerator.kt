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
import com.expensepereport.app.data.SupabaseService
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

var lastExcelError: String? = null

object DocumentGenerator {

    val MONTH_NAMES = listOf(
        "Gennaio", "Febbraio", "Marzo", "Aprile", "Maggio", "Giugno",
        "Luglio", "Agosto", "Settembre", "Ottobre", "Novembre", "Dicembre"
    )

    private val CATEGORY_COLUMN_MAP = mapOf(
        "TELEPASS" to 5,              // Column F (0-indexed: 5)
        "NOLO" to 6,                  // Column G (0-indexed: 6)
        "PARCHEGGI_CONTANTI" to 7,    // Column H (0-indexed: 7)
        "PARCHEGGI_CC" to 8,          // Column I (0-indexed: 8)
        "RISTORANTI_CONTANTI" to 9,  // Column J (0-indexed: 9)
        "RISTORANTI_CC" to 10,        // Column K (0-indexed: 10)
        "CARBURANTE_CC" to 11,        // Column L (0-indexed: 11)
        "CARBURANTE_CARTA" to 12,     // Column M (0-indexed: 12)
        "ALTRO" to 13                 // Column N (0-indexed: 13)
    )

    private fun getCellText(cell: Cell?): String {
        if (cell == null) return ""
        return when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue.trim()
            CellType.NUMERIC -> {
                val num = cell.numericCellValue
                if (num == num.toLong().toDouble()) {
                    num.toLong().toString()
                } else {
                    String.format(Locale.US, "%.2f", num)
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

    suspend fun generateExcel(
        context: Context,
        supabaseService: SupabaseService,
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
            val fis = FileInputStream(templateFile)
            val workbook = XSSFWorkbook(fis)
            fis.close()

            // Day 1 corresponds to Row 6 in Excel (0-indexed row 5)
            val rowOffset = 4

            val orangeColorIndex = IndexedColors.GOLD.index
            val yellowColorIndex = IndexedColors.YELLOW.index

            for (m in startMonth..endMonth) {
                val sheetName = MONTH_NAMES[m - 1]
                val sheet = workbook.getSheet(sheetName) ?: continue

                val spese = supabaseService.getSpeseForMonth(year, m)

                for (spesa in spese) {
                    val dateParts = spesa.data.split("-")
                    if (dateParts.size < 3) continue
                    val day = dateParts[2].toIntOrNull() ?: continue
                    val targetRowIdx = day + rowOffset

                    var row = sheet.getRow(targetRowIdx)
                    if (row == null) {
                        row = sheet.createRow(targetRowIdx)
                    }

                    // Column C: Destinazione (0-indexed col 2)
                    spesa.destinazione?.let { dest ->
                        val cell = row.getCell(2) ?: row.createCell(2)
                        val currVal = getCellText(cell)
                        if (currVal.isEmpty()) {
                            cell.setCellValue(dest)
                        } else if (!currVal.split("\\").map { it.trim() }.contains(dest)) {
                            cell.setCellValue("$currVal\\$dest")
                        }
                    }

                    // Column D: Scopo (0-indexed col 3)
                    spesa.scopo?.let { scopo ->
                        val cell = row.getCell(3) ?: row.createCell(3)
                        val currVal = getCellText(cell)
                        if (currVal.isEmpty()) {
                            cell.setCellValue(scopo)
                        } else if (!currVal.split("\\").map { it.trim() }.contains(scopo)) {
                            cell.setCellValue("$currVal\\$scopo")
                        }
                    }

                    // Column O: Note (0-indexed col 14)
                    spesa.note?.let { note ->
                        val cell = row.getCell(14) ?: row.createCell(14)
                        val currVal = getCellText(cell)
                        if (currVal.isEmpty()) {
                            cell.setCellValue(note)
                        } else if (!currVal.split(";").map { it.trim() }.contains(note)) {
                            cell.setCellValue("$currVal; $note")
                        }
                    }

                    // Value in specific Category column
                    val colIdx = CATEGORY_COLUMN_MAP[spesa.categoria]
                    if (colIdx != null && spesa.importo > 0) {
                        val cell = row.getCell(colIdx) ?: row.createCell(colIdx)
                        val impVal = String.format(Locale.US, "%.2f", spesa.importo).toDouble()

                        val existingStyle = cell.cellStyle
                        val currFillColor = existingStyle?.fillForegroundColor ?: 0

                        val isAlreadyHighlighted = currFillColor == orangeColorIndex || currFillColor == yellowColorIndex
                        val isForeign = spesa.valutaStraniera == 1

                        if (cell.cellType == CellType.BLANK || getCellText(cell).isEmpty()) {
                            cell.setCellValue(impVal)
                            if (isForeign) {
                                val newStyle = workbook.createCellStyle()
                                if (existingStyle != null) newStyle.cloneStyleFrom(existingStyle)
                                newStyle.fillForegroundColor = orangeColorIndex
                                newStyle.fillPattern = FillPatternType.SOLID_FOREGROUND
                                cell.cellStyle = newStyle
                            }
                        } else if (cell.cellType == CellType.NUMERIC) {
                            val currVal = cell.numericCellValue
                            cell.cellFormula = "$currVal+$impVal"
                            if (isForeign || isAlreadyHighlighted) {
                                val newStyle = workbook.createCellStyle()
                                if (existingStyle != null) newStyle.cloneStyleFrom(existingStyle)
                                newStyle.fillForegroundColor = yellowColorIndex
                                newStyle.fillPattern = FillPatternType.SOLID_FOREGROUND
                                cell.cellStyle = newStyle
                            }
                        } else if (cell.cellType == CellType.FORMULA) {
                            val currFormula = cell.cellFormula
                            cell.cellFormula = "$currFormula+$impVal"
                            if (isForeign || isAlreadyHighlighted) {
                                val newStyle = workbook.createCellStyle()
                                if (existingStyle != null) newStyle.cloneStyleFrom(existingStyle)
                                newStyle.fillForegroundColor = yellowColorIndex
                                newStyle.fillPattern = FillPatternType.SOLID_FOREGROUND
                                cell.cellStyle = newStyle
                            }
                        } else {
                            cell.setCellValue(impVal)
                            if (isForeign) {
                                val newStyle = workbook.createCellStyle()
                                if (existingStyle != null) newStyle.cloneStyleFrom(existingStyle)
                                newStyle.fillForegroundColor = orangeColorIndex
                                newStyle.fillPattern = FillPatternType.SOLID_FOREGROUND
                                cell.cellStyle = newStyle
                            }
                        }
                    }
                }
            }

            val filename = when (mode) {
                "anno" -> "Note_Spese_Anno_${year}.xlsx"
                "range" -> "Note_Spese_${MONTH_NAMES[startMonth-1]}_${MONTH_NAMES[endMonth-1]}_${year}.xlsx"
                else -> "Note_Spese_${MONTH_NAMES[startMonth-1]}_${year}.xlsx"
            }

            val outFile = File(context.cacheDir, filename)
            val fos = FileOutputStream(outFile)
            workbook.write(fos)
            fos.close()
            workbook.close()

            outFile
        } catch (t: Throwable) {
            t.printStackTrace()
            lastExcelError = "${t.javaClass.simpleName}: ${t.message}"
            null
        }
    }

    suspend fun generatePdfAttachments(
        context: Context,
        supabaseService: SupabaseService,
        year: Int,
        month: Int
    ): File? {
        return try {
            val spese = supabaseService.getSpeseForMonth(year, month)
            val speseWithAttachment = spese.filter { !it.allegatoPath.isNullOrBlank() }

            if (speseWithAttachment.isEmpty()) return null

            val pdfDocument = PdfDocument()

            // Standard A4 dimensions in points: 595 x 842
            val pageWidth = 595
            val pageHeight = 842

            var pageNumber = 1
            var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
            var page = pdfDocument.startPage(pageInfo)
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

            val highQualityBitmapPaint = Paint().apply {
                isAntiAlias = true
                isFilterBitmap = true
                isDither = true
            }

            // Draw Header Title
            canvas.drawText("Allegati Spese (${speseWithAttachment.size} Voci) - ${MONTH_NAMES[month - 1]} $year", 30f, 40f, titlePaint)

            val pendingPdfsToMerge = mutableListOf<Pair<Spesa, ByteArray>>()

            // Grid Layout: 2 columns x 2 rows = 4 items per page
            val colPositions = floatArrayOf(30f, 305f)
            val rowPositions = floatArrayOf(70f, 440f)

            val maxCellWidth = 260f
            val maxCellHeight = 290f

            for ((index, spesa) in speseWithAttachment.withIndex()) {
                val pageItemIndex = index % 4

                if (index > 0 && pageItemIndex == 0) {
                    pdfDocument.finishPage(page)
                    pageNumber++
                    pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                    page = pdfDocument.startPage(pageInfo)
                    canvas = page.canvas
                }

                val colIdx = pageItemIndex % 2
                val rowIdx = pageItemIndex / 2

                val startX = colPositions[colIdx]
                val startY = rowPositions[rowIdx]

                // Item Header Info
                canvas.drawText("ID #${spesa.id ?: "-"} | Data: ${spesa.data}", startX, startY + 12f, boldPaint)
                canvas.drawText("Importo: € ${String.format(Locale.US, "%.2f", spesa.importo)}", startX, startY + 26f, boldPaint)
                canvas.drawText("Destinazione: ${spesa.destinazione ?: "-"}", startX, startY + 40f, bodyPaint)
                canvas.drawText("Cat: ${spesa.categoria} | Pag: ${spesa.metodoPagamento ?: "Standard"}", startX, startY + 54f, bodyPaint)

                val url = spesa.allegatoPath!!
                val isPdf = url.lowercase().contains(".pdf")

                val attachmentBytes = supabaseService.downloadBytes(url)

                if (attachmentBytes != null) {
                    if (isPdf) {
                        pendingPdfsToMerge.add(Pair(spesa, attachmentBytes))
                        canvas.drawText("📄 [Documento PDF allegato e unito in coda]", startX, startY + 80f, boldPaint)
                    } else {
                        try {
                            val options = BitmapFactory.Options().apply {
                                inScaled = false
                                inPreferredConfig = Bitmap.Config.ARGB_8888
                            }
                            // Decode original image without downsampling to retain 100% pixel detail
                            val originalBitmap = BitmapFactory.decodeByteArray(attachmentBytes, 0, attachmentBytes.size, options)
                            if (originalBitmap != null) {
                                val grayBitmap = convertToGrayscale(originalBitmap)

                                // Calculate destination bounds in points preserving aspect ratio
                                val srcWidth = grayBitmap.width.toFloat()
                                val srcHeight = grayBitmap.height.toFloat()
                                val ratio = Math.min(maxCellWidth / srcWidth, maxCellHeight / srcHeight)

                                val destWidth = srcWidth * ratio
                                val destHeight = srcHeight * ratio

                                val imageTop = startY + 65f
                                val srcRect = Rect(0, 0, grayBitmap.width, grayBitmap.height)
                                val dstRect = RectF(startX, imageTop, startX + destWidth, imageTop + destHeight)

                                // Draw full resolution bitmap directly using destination bounds
                                canvas.drawBitmap(grayBitmap, srcRect, dstRect, highQualityBitmapPaint)

                                grayBitmap.recycle()
                                originalBitmap.recycle()
                            } else {
                                canvas.drawText("[Immagine non visualizzabile]", startX, startY + 80f, bodyPaint)
                            }
                        } catch (e: Exception) {
                            canvas.drawText("[Errore caricamento immagine]", startX, startY + 80f, bodyPaint)
                        }
                    }
                } else {
                    canvas.drawText("[Impossibile scaricare allegato]", startX, startY + 80f, bodyPaint)
                }
            }

            pdfDocument.finishPage(page)

            // Render and append PDF attachment pages at 4x high resolution (~300 DPI)
            for ((spesa, pdfBytes) in pendingPdfsToMerge) {
                try {
                    val tempPdfFile = File(context.cacheDir, "temp_attachment_${spesa.id}.pdf")
                    tempPdfFile.writeBytes(pdfBytes)

                    val fileDescriptor = ParcelFileDescriptor.open(tempPdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
                    val pdfRenderer = PdfRenderer(fileDescriptor)

                    for (i in 0 until pdfRenderer.pageCount) {
                        val pdfPage = pdfRenderer.openPage(i)

                        pageNumber++
                        pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                        page = pdfDocument.startPage(pageInfo)
                        canvas = page.canvas

                        canvas.drawText("Allegato PDF in Coda - ID #${spesa.id ?: "-"} (Pagina ${i + 1}/${pdfRenderer.pageCount})", 30f, 30f, boldPaint)

                        // Render PDF page at 4x scale (~300 DPI) for original print quality
                        val scaleFactor = 4f
                        val highResWidth = (pdfPage.width * scaleFactor).toInt()
                        val highResHeight = (pdfPage.height * scaleFactor).toInt()

                        val highResBitmap = Bitmap.createBitmap(highResWidth, highResHeight, Bitmap.Config.ARGB_8888)
                        pdfPage.render(highResBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                        val grayBitmap = convertToGrayscale(highResBitmap)

                        val availableWidth = pageWidth - 60f
                        val availableHeight = pageHeight - 80f

                        val srcWidth = grayBitmap.width.toFloat()
                        val srcHeight = grayBitmap.height.toFloat()
                        val ratio = Math.min(availableWidth / srcWidth, availableHeight / srcHeight)

                        val destWidth = srcWidth * ratio
                        val destHeight = srcHeight * ratio

                        val srcRect = Rect(0, 0, grayBitmap.width, grayBitmap.height)
                        val dstRect = RectF(30f, 50f, 30f + destWidth, 50f + destHeight)

                        canvas.drawBitmap(grayBitmap, srcRect, dstRect, highQualityBitmapPaint)

                        grayBitmap.recycle()
                        highResBitmap.recycle()

                        pdfDocument.finishPage(page)
                        pdfPage.close()
                    }

                    pdfRenderer.close()
                    fileDescriptor.close()
                    tempPdfFile.delete()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            val pdfFile = File(context.cacheDir, "Allegati_Spese_${MONTH_NAMES[month - 1]}_${year}.pdf")
            val fos = FileOutputStream(pdfFile)
            pdfDocument.writeTo(fos)
            fos.close()
            pdfDocument.close()

            pdfFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun convertToGrayscale(bmpOriginal: Bitmap): Bitmap {
        val width = bmpOriginal.width
        val height = bmpOriginal.height
        val bmpGrayscale = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmpGrayscale)
        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }
        val cm = ColorMatrix()
        cm.setSaturation(0f)
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(bmpOriginal, 0f, 0f, paint)
        return bmpGrayscale
    }
}
