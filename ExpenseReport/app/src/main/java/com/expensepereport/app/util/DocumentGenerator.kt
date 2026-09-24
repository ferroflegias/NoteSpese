package com.expensepereport.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.expensepereport.app.data.Spesa
import com.expensepereport.app.data.SupabaseService
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
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

    private val dataFormatter = DataFormatter()

    private fun getCellText(cell: Cell?): String {
        if (cell == null) return ""
        return dataFormatter.formatCellValue(cell).trim()
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

            // Day 1 corresponds to Row 5 in Excel (0-indexed row 4)
            val rowOffset = 3

            val fillOrange = workbook.createCellStyle()
            fillOrange.fillForegroundColor = IndexedColors.GOLD.index
            fillOrange.fillPattern = FillPatternType.SOLID_FOREGROUND

            val fillYellow = workbook.createCellStyle()
            fillYellow.fillForegroundColor = IndexedColors.YELLOW.index
            fillYellow.fillPattern = FillPatternType.SOLID_FOREGROUND

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

                        if (cell.cellType == CellType.BLANK || getCellText(cell).isEmpty()) {
                            cell.setCellValue(impVal)
                            if (spesa.valutaStraniera == 1) {
                                val newStyle = workbook.createCellStyle()
                                if (existingStyle != null) newStyle.cloneStyleFrom(existingStyle)
                                newStyle.fillForegroundColor = IndexedColors.GOLD.index
                                newStyle.fillPattern = FillPatternType.SOLID_FOREGROUND
                                cell.cellStyle = newStyle
                            }
                        } else if (cell.cellType == CellType.NUMERIC) {
                            val currVal = cell.numericCellValue
                            cell.cellFormula = "$currVal+$impVal"
                            val newStyle = workbook.createCellStyle()
                            if (existingStyle != null) newStyle.cloneStyleFrom(existingStyle)
                            newStyle.fillForegroundColor = IndexedColors.YELLOW.index
                            newStyle.fillPattern = FillPatternType.SOLID_FOREGROUND
                            cell.cellStyle = newStyle
                        } else if (cell.cellType == CellType.FORMULA) {
                            val currFormula = cell.cellFormula
                            cell.cellFormula = "$currFormula+$impVal"
                            val newStyle = workbook.createCellStyle()
                            if (existingStyle != null) newStyle.cloneStyleFrom(existingStyle)
                            newStyle.fillForegroundColor = IndexedColors.YELLOW.index
                            newStyle.fillPattern = FillPatternType.SOLID_FOREGROUND
                            cell.cellStyle = newStyle
                        } else {
                            cell.setCellValue(impVal)
                            if (spesa.valutaStraniera == 1) {
                                val newStyle = workbook.createCellStyle()
                                if (existingStyle != null) newStyle.cloneStyleFrom(existingStyle)
                                newStyle.fillForegroundColor = IndexedColors.GOLD.index
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
            }

            val bodyPaint = Paint().apply {
                color = android.graphics.Color.BLACK
                textSize = 9f
            }

            val boldPaint = Paint().apply {
                color = android.graphics.Color.BLACK
                textSize = 10f
                isFakeBoldText = true
            }

            // Draw Header Title
            canvas.drawText("Allegati Spese (${speseWithAttachment.size} Voci) - ${MONTH_NAMES[month - 1]} $year", 30f, 40f, titlePaint)

            val pendingPdfsToMerge = mutableListOf<Pair<Spesa, ByteArray>>()

            // Grid Layout: 2 columns x 2 rows = 4 items per page
            val colPositions = floatArrayOf(30f, 305f)
            val rowPositions = floatArrayOf(70f, 440f)

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
                            val bitmap = BitmapFactory.decodeByteArray(attachmentBytes, 0, attachmentBytes.size)
                            if (bitmap != null) {
                                val grayBitmap = convertToGrayscale(bitmap)
                                val maxW = 250
                                val maxH = 280
                                val scaledBitmap = scaleBitmapToFit(grayBitmap, maxW, maxH)

                                canvas.drawBitmap(scaledBitmap, startX, startY + 65f, null)
                                grayBitmap.recycle()
                                bitmap.recycle()
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

            // Render and append PDF attachment pages to the end of pdfDocument using PdfRenderer
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

                        // Render PDF page to a Bitmap
                        val bitmap = Bitmap.createBitmap(pdfPage.width, pdfPage.height, Bitmap.Config.ARGB_8888)
                        pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                        val grayBitmap = convertToGrayscale(bitmap)
                        val scaledBitmap = scaleBitmapToFit(grayBitmap, pageWidth - 60, pageHeight - 80)

                        canvas.drawBitmap(scaledBitmap, 30f, 50f, null)

                        grayBitmap.recycle()
                        bitmap.recycle()

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
        val paint = Paint()
        val cm = ColorMatrix()
        cm.setSaturation(0f)
        val f = ColorMatrixColorFilter(cm)
        paint.colorFilter = f
        canvas.drawBitmap(bmpOriginal, 0f, 0f, paint)
        return bmpGrayscale
    }

    private fun scaleBitmapToFit(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val ratio = Math.min(maxWidth.toFloat() / width, maxHeight.toFloat() / height)
        val newWidth = Math.max(1, (width * ratio).toInt())
        val newHeight = Math.max(1, (height * ratio).toInt())
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
}
