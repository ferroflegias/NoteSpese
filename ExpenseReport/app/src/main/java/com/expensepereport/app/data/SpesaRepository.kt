package com.expensepereport.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.expensepereport.app.data.room.AppDatabase
import com.expensepereport.app.data.room.SpesaDao
import com.expensepereport.app.data.room.SpesaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
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

    suspend fun importFromSqliteFile(inputFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val externalDb = SQLiteDatabase.openDatabase(
                inputFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )

            val cursor = externalDb.rawQuery("SELECT * FROM spese", null)
            val importedEntities = mutableListOf<SpesaEntity>()

            if (cursor.moveToFirst()) {
                val idIdx = cursor.getColumnIndex("id")
                val dataIdx = cursor.getColumnIndex("data")
                val destIdx = cursor.getColumnIndex("destinazione")
                val scopoIdx = cursor.getColumnIndex("scopo")
                val catIdx = cursor.getColumnIndex("categoria")
                val metIdx = cursor.getColumnIndex("metodo_pagamento")
                val impIdx = cursor.getColumnIndex("importo")
                val kmIdx = cursor.getColumnIndex("km")
                val noteIdx = cursor.getColumnIndex("note")
                val allIdx = cursor.getColumnIndex("allegato_path")
                val valIdx = cursor.getColumnIndex("valuta_straniera")
                val creIdx = cursor.getColumnIndex("created_at")

                do {
                    val id = if (idIdx != -1 && !cursor.isNull(idIdx)) cursor.getLong(idIdx) else 0L
                    val data = if (dataIdx != -1 && !cursor.isNull(dataIdx)) cursor.getString(dataIdx) else ""
                    val dest = if (destIdx != -1 && !cursor.isNull(destIdx)) cursor.getString(destIdx) else null
                    val scopo = if (scopoIdx != -1 && !cursor.isNull(scopoIdx)) cursor.getString(scopoIdx) else null
                    val cat = if (catIdx != -1 && !cursor.isNull(catIdx)) cursor.getString(catIdx) else "ALTRO"
                    val met = if (metIdx != -1 && !cursor.isNull(metIdx)) cursor.getString(metIdx) else null
                    val imp = if (impIdx != -1 && !cursor.isNull(impIdx)) cursor.getDouble(impIdx) else 0.0
                    val km = if (kmIdx != -1 && !cursor.isNull(kmIdx)) cursor.getDouble(kmIdx) else 0.0
                    val note = if (noteIdx != -1 && !cursor.isNull(noteIdx)) cursor.getString(noteIdx) else null
                    val allegato = if (allIdx != -1 && !cursor.isNull(allIdx)) cursor.getString(allIdx) else null
                    val valuta = if (valIdx != -1 && !cursor.isNull(valIdx)) cursor.getInt(valIdx) else 0
                    val created = if (creIdx != -1 && !cursor.isNull(creIdx)) cursor.getString(creIdx) else null

                    if (data.isNotBlank()) {
                        importedEntities.add(
                            SpesaEntity(
                                id = id,
                                data = data,
                                destinazione = dest,
                                scopo = scopo,
                                categoria = cat,
                                metodoPagamento = met,
                                importo = imp,
                                km = km,
                                note = note,
                                allegatoPath = allegato,
                                valutaStraniera = valuta,
                                createdAt = created
                            )
                        )
                    }
                } while (cursor.moveToNext())
            }

            cursor.close()
            externalDb.close()

            if (importedEntities.isNotEmpty()) {
                spesaDao.deleteAll()
                spesaDao.insertAll(importedEntities)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun importFromJsonFile(inputFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val jsonText = inputFile.readText(Charsets.UTF_8)
            val jsonArray = JSONArray(jsonText)
            val importedEntities = mutableListOf<SpesaEntity>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.optLong("id", 0L)
                val data = obj.optString("data", "")
                val dest = if (obj.has("destinazione") && !obj.isNull("destinazione")) obj.getString("destinazione") else null
                val scopo = if (obj.has("scopo") && !obj.isNull("scopo")) obj.getString("scopo") else null
                val cat = obj.optString("categoria", "ALTRO")
                val met = if (obj.has("metodo_pagamento") && !obj.isNull("metodo_pagamento")) obj.getString("metodo_pagamento") else null
                val imp = obj.optDouble("importo", 0.0)
                val km = obj.optDouble("km", 0.0)
                val note = if (obj.has("note") && !obj.isNull("note")) obj.getString("note") else null
                val allegato = if (obj.has("allegato_path") && !obj.isNull("allegato_path")) obj.getString("allegato_path") else null
                val valuta = obj.optInt("valuta_straniera", 0)
                val created = if (obj.has("created_at") && !obj.isNull("created_at")) obj.getString("created_at") else null

                if (data.isNotBlank()) {
                    importedEntities.add(
                        SpesaEntity(
                            id = id,
                            data = data,
                            destinazione = dest,
                            scopo = scopo,
                            categoria = cat,
                            metodoPagamento = met,
                            importo = imp,
                            km = km,
                            note = note,
                            allegatoPath = allegato,
                            valutaStraniera = valuta,
                            createdAt = created
                        )
                    )
                }
            }

            if (importedEntities.isNotEmpty()) {
                spesaDao.deleteAll()
                spesaDao.insertAll(importedEntities)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun importDatabaseFromUri(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val tempFile = File(context.cacheDir, "temp_import.file")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            if (!tempFile.exists() || tempFile.length() == 0L) {
                return@withContext false
            }

            // Try importing as SQLite database first, then fallback to JSON if needed
            var success = importFromSqliteFile(tempFile)
            if (!success) {
                success = importFromJsonFile(tempFile)
            }

            tempFile.delete()
            success
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun restoreDatabaseFromFile(inputFile: File): Boolean = withContext(Dispatchers.IO) {
        var success = importFromSqliteFile(inputFile)
        if (!success) {
            success = importFromJsonFile(inputFile)
        }
        success
    }
}
