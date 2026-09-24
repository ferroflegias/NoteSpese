package com.expensepereport.app

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ExcelTest {
    @Test
    fun testPoiXssf() {
        try {
            val wb = XSSFWorkbook()
            val sheet = wb.createSheet("Gennaio")
            val row = sheet.createRow(0)
            val cell = row.createCell(0)
            cell.setCellValue("Test")
            val baos = ByteArrayOutputStream()
            wb.write(baos)
            wb.close()
            println("POI test successful! Bytes: ${baos.toByteArray().size}")
        } catch (t: Throwable) {
            t.printStackTrace()
            throw t
        }
    }
}
