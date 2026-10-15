package com.expensepereport.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.expensepereport.app.util.DatabaseBackupWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit

private val Context.dataStore by preferencesDataStore(name = "settings")

class AppSettingsRepository(private val context: Context) {

    companion object {
        private val KEY_BACKUP_ENABLED = booleanPreferencesKey("backup_enabled")
        private val KEY_BACKUP_HOUR = intPreferencesKey("backup_hour")
        private val KEY_BACKUP_MINUTE = intPreferencesKey("backup_minute")
        private val KEY_GDRIVE_FOLDER_URL = stringPreferencesKey("gdrive_folder_url")
        private val KEY_GOOGLE_ACCOUNT_EMAIL = stringPreferencesKey("google_account_email")
        private const val WORK_NAME_BACKUP = "daily_database_backup"
    }

    val backupEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_BACKUP_ENABLED] ?: true
    }

    val backupHourFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_BACKUP_HOUR] ?: 2
    }

    val backupMinuteFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_BACKUP_MINUTE] ?: 0
    }

    val gdriveFolderUrlFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_GDRIVE_FOLDER_URL] ?: ""
    }

    val googleAccountEmailFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_GOOGLE_ACCOUNT_EMAIL] ?: ""
    }

    suspend fun saveGoogleAccountEmail(email: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_GOOGLE_ACCOUNT_EMAIL] = email.trim()
        }
    }

    suspend fun saveGdriveFolderUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_GDRIVE_FOLDER_URL] = url.trim()
        }
    }

    suspend fun saveBackupSettings(enabled: Boolean, hour: Int, minute: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BACKUP_ENABLED] = enabled
            prefs[KEY_BACKUP_HOUR] = hour
            prefs[KEY_BACKUP_MINUTE] = minute
        }
        if (enabled) {
            scheduleDailyBackup(hour, minute)
        } else {
            cancelDailyBackup()
        }
    }

    fun scheduleDailyBackup(hour: Int, minute: Int) {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (target.before(now)) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }

        val initialDelay = target.timeInMillis - now.timeInMillis

        val backupWorkRequest = PeriodicWorkRequestBuilder<DatabaseBackupWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME_BACKUP,
            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
            backupWorkRequest
        )
    }

    fun cancelDailyBackup() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_BACKUP)
    }

    fun saveExcelTemplate(inputBytes: ByteArray): Boolean {
        return try {
            val file = File(context.filesDir, "Note_spese_2026_custom_template.xlsx")
            file.writeBytes(inputBytes)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun getExcelTemplateFile(): File? {
        val file = File(context.filesDir, "Note_spese_2026_custom_template.xlsx")
        return if (file.exists()) file else null
    }

    fun hasCustomExcelTemplate(): Boolean {
        return getExcelTemplateFile() != null
    }
}
