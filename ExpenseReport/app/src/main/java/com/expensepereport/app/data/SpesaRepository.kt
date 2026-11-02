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

    suspend fun deleteSpesa(id: Long) = withContext(Dispatchers.IO) {
        spesaDao.deleteById(id)
    }

    fun getDatabaseFile(): File {
        return context.getDatabasePath("expense_report.db")
    }

    /**
     * Room uses WAL journaling: the latest writes may still live in the -wal file.
     * Checkpoint first so that the main .db file is a complete, consistent copy.
     */
    suspend fun getDatabaseFileForBackup(): File = withContext(Dispatchers.IO) {
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        getDatabaseFile()
    }

    private fun isSqliteFile(file: File): Boolean {
        val header = ByteArray(SQLITE_HEADER.size)
        val read = file.inputStream().use { it.read(header) }
        return read == header.size && header.contentEquals(SQLITE_HEADER)
    }

    private fun readEntitiesFromSqlite(inputFile: File): List<SpesaEntity> {
        val entities = mutableListOf<SpesaEntity>()
        SQLiteDatabase.openDatabase(inputFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { externalDb ->
            externalDb.rawQuery("SELECT * FROM spese", null).use { cursor ->
                fun col(name: String): Int? = cursor.getColumnIndex(name).takeIf { it != -1 && !cursor.isNull(it) }

                while (cursor.moveToNext()) {
                    val data = col("data")?.let { cursor.getString(it) } ?: ""
                    if (data.isBlank()) continue
                    entities.add(
                        SpesaEntity(
                            id = col("id")?.let { cursor.getLong(it) } ?: 0L,
                            data = data,
                            destinazione = col("destinazione")?.let { cursor.getString(it) },
                            scopo = col("scopo")?.let { cursor.getString(it) },
                            categoria = col("categoria")?.let { cursor.getString(it) } ?: "ALTRO",
                            metodoPagamento = col("metodo_pagamento")?.let { cursor.getString(it) },
                            importo = col("importo")?.let { cursor.getDouble(it) } ?: 0.0,
                            km = col("km")?.let { cursor.getDouble(it) } ?: 0.0,
                            note = col("note")?.let { cursor.getString(it) },
                            allegatoPath = col("allegato_path")?.let { cursor.getString(it) },
                            valutaStraniera = col("valuta_straniera")?.let { cursor.getInt(it) } ?: 0,
                            createdAt = col("created_at")?.let { cursor.getString(it) }
                        )
                    )
                }
            }
        }
        return entities
    }

    private fun readEntitiesFromJson(inputFile: File): List<SpesaEntity> {
        val jsonArray = JSONArray(inputFile.readText(Charsets.UTF_8))
        val entities = mutableListOf<SpesaEntity>()

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            fun optStringOrNull(name: String): String? = if (obj.isNull(name)) null else obj.getString(name)

            val data = obj.optString("data", "")
            if (data.isBlank()) continue
            entities.add(
                SpesaEntity(
                    id = obj.optLong("id", 0L),
                    data = data,
                    destinazione = optStringOrNull("destinazione"),
                    scopo = optStringOrNull("scopo"),
                    categoria = obj.optString("categoria", "ALTRO"),
                    metodoPagamento = optStringOrNull("metodo_pagamento"),
                    importo = obj.optDouble("importo", 0.0),
                    km = obj.optDouble("km", 0.0),
                    note = optStringOrNull("note"),
                    allegatoPath = optStringOrNull("allegato_path"),
                    valutaStraniera = obj.optInt("valuta_straniera", 0),
                    createdAt = optStringOrNull("created_at")
                )
            )
        }
        return entities
    }

    /**
     * Replaces all expenses with the content of [inputFile] (SQLite database or JSON array).
     * The format is detected from the file header: opening a non-SQLite file with
     * SQLiteDatabase would trigger the corruption handler, which deletes the file.
     */
    suspend fun importFromFile(inputFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val entities = if (isSqliteFile(inputFile)) {
                readEntitiesFromSqlite(inputFile)
            } else {
                readEntitiesFromJson(inputFile)
            }
            if (entities.isEmpty()) return@withContext false
            spesaDao.replaceAll(entities)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun importDatabaseFromUri(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val tempFile = File(context.cacheDir, "temp_import.file")
        try {
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            }
            if (copied == null || tempFile.length() == 0L) {
                return@withContext false
            }
            importFromFile(tempFile)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            tempFile.delete()
        }
    }

    companion object {
        private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    }
}
