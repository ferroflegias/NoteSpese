package com.expensepereport.app.data

import android.content.Context
import android.net.Uri
import com.expensepereport.app.data.room.AppDatabase
import com.expensepereport.app.data.room.SpesaDao
import com.expensepereport.app.data.room.SpesaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SpesaRepository(private val context: Context) {
    private val db: AppDatabase = AppDatabase.getDatabase(context)
    private val spesaDao: SpesaDao = db.spesaDao()

    suspend fun getSpese(): List<Spesa> = withContext(Dispatchers.IO) {
        spesaDao.getAllSpese().map { it.toSpesa() }
    }

    suspend fun getSpeseForMonth(year: Int, month: Int): List<Spesa> = withContext(Dispatchers.IO) {
        val startDate = String.format(Locale.US, "%04d-%02d-01", year, month)
        val endDate = if (month == 12) {
            String.format(Locale.US, "%04d-01-01", year + 1)
        } else {
            String.format(Locale.US, "%04d-%02d-01", year, month + 1)
        }
        spesaDao.getSpeseForRange(startDate, endDate).map { it.toSpesa() }
    }

    suspend fun insertSpesa(spesaInsert: SpesaInsert): Long = withContext(Dispatchers.IO) {
        val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val entity = SpesaEntity(
            data = spesaInsert.data,
            destinazione = spesaInsert.destinazione,
            scopo = spesaInsert.scopo,
            categoria = spesaInsert.categoria,
            metodoPagamento = spesaInsert.metodoPagamento,
            importo = spesaInsert.importo,
            km = spesaInsert.km,
            note = spesaInsert.note,
            allegatoPath = spesaInsert.allegatoPath,
            valutaStraniera = spesaInsert.valutaStraniera,
            createdAt = nowStr
        )
        spesaDao.insert(entity)
    }

    suspend fun updateSpesa(id: Long, spesaInsert: SpesaInsert): Boolean = withContext(Dispatchers.IO) {
        val existing = spesaDao.getSpesaById(id) ?: return@withContext false
        val updated = existing.copy(
            data = spesaInsert.data,
            destinazione = spesaInsert.destinazione,
            scopo = spesaInsert.scopo,
            categoria = spesaInsert.categoria,
            metodoPagamento = spesaInsert.metodoPagamento,
            importo = spesaInsert.importo,
            km = spesaInsert.km,
            note = spesaInsert.note,
            allegatoPath = spesaInsert.allegatoPath ?: existing.allegatoPath,
            valutaStraniera = spesaInsert.valutaStraniera
        )
        spesaDao.update(updated)
        true
    }

    suspend fun deleteSpesa(id: Long): Boolean = withContext(Dispatchers.IO) {
        spesaDao.deleteById(id)
        true
    }

    suspend fun deleteAllSpese() = withContext(Dispatchers.IO) {
        spesaDao.deleteAll()
    }

    fun getDatabaseFile(): File {
        return context.getDatabasePath("expense_report.db")
    }

    suspend fun restoreDatabaseFromFile(inputFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            db.close()
            val dbFile = getDatabaseFile()
            if (dbFile.exists()) {
                dbFile.delete()
            }
            inputFile.copyTo(dbFile, overwrite = true)
            // Reopen database by fetching instance
            AppDatabase.getDatabase(context)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun importDatabaseFromUri(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val tempFile = File(context.cacheDir, "temp_import.db")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (tempFile.exists() && tempFile.length() > 0) {
                val success = restoreDatabaseFromFile(tempFile)
                tempFile.delete()
                success
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
