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

    suspend fun insertSpesa(spesa: Spesa): Long = withContext(Dispatchers.IO) {
        val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        spesaDao.insert(SpesaEntity.fromSpesa(spesa.copy(id = null, createdAt = nowStr)))
    }

    /** Saves the changes to an existing expense (identified by [Spesa.id]). */
    suspend fun updateSpesa(spesa: Spesa) = withContext(Dispatchers.IO) {
        require(spesa.id != null) { "Spesa senza id" }
        spesaDao.update(SpesaEntity.fromSpesa(spesa))
    }

    suspend fun deleteSpesa(id: Long) = withContext(Dispatchers.IO) {
        spesaDao.deleteById(id)
    }

    private fun getDatabaseFile(): File = context.getDatabasePath(AppDatabase.DATABASE_NAME)

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
    suspend fun importFromFile(inputFile: File): ImportResult = withContext(Dispatchers.IO) {
        try {
            val entities = if (isSqliteFile(inputFile)) {
                if (!hasSpeseTable(inputFile)) return@withContext ImportResult.NO_EXPENSES_TABLE
                readEntitiesFromSqlite(inputFile)
            } else {
                readEntitiesFromJson(inputFile)
            }
            if (entities.isEmpty()) return@withContext ImportResult.EMPTY
            spesaDao.replaceAll(entities)
            ImportResult.SUCCESS
        } catch (e: Exception) {
            e.printStackTrace()
            ImportResult.INVALID_FILE
        }
    }

    /**
     * Backups made by older versions copied the .db file without a WAL checkpoint:
     * when all the data was still in the -wal file, the copy has no "spese" table.
     */
    private fun hasSpeseTable(inputFile: File): Boolean =
        SQLiteDatabase.openDatabase(inputFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { externalDb ->
            externalDb.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'spese'", null
            ).use { it.moveToFirst() }
        }

    suspend fun importDatabaseFromUri(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val tempFile = File(context.cacheDir, "temp_import.file")
        try {
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            }
            if (copied == null || tempFile.length() == 0L) {
                return@withContext ImportResult.INVALID_FILE
            }
            importFromFile(tempFile)
        } catch (e: Exception) {
            e.printStackTrace()
            ImportResult.INVALID_FILE
        } finally {
            tempFile.delete()
        }
    }

    companion object {
        private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    }
}

enum class ImportResult {
    SUCCESS,
    /** The file is a valid database or JSON array but contains no expenses. */
    EMPTY,
    /** SQLite database without the "spese" table (e.g. a backup taken without WAL checkpoint). */
    NO_EXPENSES_TABLE,
    /** Unreadable, corrupted or unsupported file. */
    INVALID_FILE;

    /** User-facing description of a failed import. */
    fun errorMessage(): String = when (this) {
        SUCCESS -> ""
        EMPTY -> "Il file non contiene nessuna spesa: è un backup vuoto."
        NO_EXPENSES_TABLE -> "Il file non contiene la tabella delle spese: probabilmente è un backup incompleto, creato da una versione precedente dell'app. Prova un backup più grande o più recente."
        INVALID_FILE -> "Il file non è un database SQLite o un file JSON valido, oppure è danneggiato."
    }
}
