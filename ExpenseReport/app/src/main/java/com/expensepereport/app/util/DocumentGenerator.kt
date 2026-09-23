package com.expensepereport.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.expensepereport.app.data.Spesa
import com.expensepereport.app.data.SupabaseService
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.xssf.usermodel.XSSFColor
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    suspend fun generateExcel(
        context: Context,
        supabaseService: SupabaseService,
        templateFile: File,
        year: Int,
        mode: String, // "singolo", "range", "anno"
        startMonth: Int,
        endMonth: Int
    ): File? {
        if (!templateFile.exists()) return null

        return try {
            val fis = FileInputStream(templateFile)
            val workbook = XSSFWorkbook(fis)
            fis.close()

            val rowOffset = 4 // 0-indexed row 4 is Row 5 in Excel

            // Prepare fills
            val fillOrange = workbook.createCellStyle()
            val orangeColor = XSSFColor(byteArrayOf(0xFF.toByte(), 0xC0.toByte(), 0x00.toByte()), null)
            fillOrange.setFillForegroundColor(orangeColor)
            fillOrange.fillPattern = FillPatternType.SOLID_FOREGROUND

            val fillYellow = workbook.createCellStyle()
            val yellowColor = XSSFColor(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x00.toByte()), null)
            fillYellow.setFillForegroundColor(yellowColor)
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
                        val currVal = cell.stringCellValue.trim()
                        if (currVal.isEmpty()) {
                            cell.setCellValue(dest)
                        } else if (!currVal.split("\\").map { it.trim() }.contains(dest)) {
                            cell.setCellValue("$currVal\\$dest")
                        }
                    }

                    // Column D: Scopo (0-indexed col 3)
                    spesa.scopo?.let { scopo ->
                        val cell = row.getCell(3) ?: row.createCell(3)
                        val currVal = cell.stringCellValue.trim()
                        if (currVal.isEmpty()) {
                            cell.setCellValue(scopo)
                        } else if (!currVal.split("\\").map { it.trim() }.contains(scopo)) {
                            cell.setCellValue("$currVal\\$scopo")
                        }
                    }

                    // Column O: Note (0-indexed col 14)
                    spesa.note?.let { note ->
                        val cell = row.getCell(14) ?: row.createCell(14)
                        val currVal = cell.stringCellValue.trim()
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

                        if (cell.cellType == org.apache.poi.ss.usermodel.CellType.BLANK || cell.stringCellValue.isBlank() && cell.numericCellValue == 0.0) {
                            cell.setCellValue(impVal)
                            if (spesa.valutaStraniera == 1) {
                                cell.cellStyle = fillOrange
                            }
                        } else if (cell.cellType == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
                            val currVal = cell.numericCellValue
                            cell.cellFormula = "$currVal+$impVal"
                            cell.cellStyle = fillYellow
                        } else if (cell.cellType == org.apache.poi.ss.usermodel.CellType.FORMULA) {
                            val currFormula = cell.cellFormula
                            cell.cellFormula = "$currFormula+$impVal"
                            cell.cellStyle = fillYellow
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
        } catch (e: Exception) {
            e.printStackTrace()
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
                textSize = 10f
            }

            val boldPaint = Paint().apply {
                color = android.graphics.Color.BLACK
                textSize = 10f
                isFakeBoldText = true
            }

            // Draw Header Title
            canvas.drawText("Allegati Spese (${speseWithAttachment.size} Voci) - ${MONTH_NAMES[month - 1]} $year", 30f, 40f, titlePaint)

            var currentY = 70f
            val itemWidth = 260f
            val maxImageHeight = 280f

            for ((index, spesa) in speseWithAttachment.withIndex()) {
                val isRightColumn = index % 2 == 1
                val startX = if (isRightColumn) 300f else 30f

                if (!isRightColumn && currentY + 320f > pageHeight - 30f) {
                    pdfDocument.finishPage(page)
                    pageNumber++
                    pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                    page = pdfDocument.startPage(pageInfo)
                    canvas = page.canvas
                    currentY = 40f
                }

                val itemY = currentY

                // Draw Text Info
                canvas.drawText("ID #${spesa.id ?: "-"} | Data: ${spesa.data}", startX, itemY + 12f, boldPaint)
                canvas.drawText("Importo: € ${String.format(Locale.US, "%.2f", spesa.importo)}", startX, itemY + 26f, boldPaint)
                canvas.drawText("Destinazione: ${spesa.destinazione ?: "-"}", startX, itemY + 40f, bodyPaint)
                canvas.drawText("Cat: ${spesa.categoria} | Pag: ${spesa.metodoPagamento ?: "Standard"}", startX, itemY + 54f, bodyPaint)

                // Download & Process Image Attachment
                val url = spesa.allegatoPath!!
                val imageBytes = supabaseService.downloadBytes(url)

                if (imageBytes != null) {
                    try {
                        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                        if (bitmap != null) {
                            val grayBitmap = convertToGrayscale(bitmap)
                            val scaledBitmap = scaleBitmapToFit(grayBitmap, itemWidth.toInt(), maxImageHeight.toInt())

                            canvas.drawBitmap(scaledBitmap, startX, itemY + 65f, null)
                        } else {
                            canvas.drawText("[Allegato non visualizzabile]", startX, itemY + 75f, bodyPaint)
                        }
                    } catch (e: Exception) {
                        canvas.drawText("[Errore allegato]", startX, itemY + 75f, bodyPaint)
                    }
                } else {
                    canvas.drawText("[Impossibile scaricare allegato]", startX, itemY + 75f, bodyPaint)
                }

                if (isRightColumn) {
                    currentY += 340f
                }
            }

            pdfDocument.finishPage(page)

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
