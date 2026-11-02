package com.expensepereport.app.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.expensepereport.app.util.DatabaseBackupWorker
import com.expensepereport.app.util.sanitizeDeviceName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit

private val Context.dataStore by preferencesDataStore(name = "settings")

class AppSettingsRepository(private val context: Context) {

    companion object {
        private val KEY_BACKUP_ENABLED = booleanPreferencesKey("backup_enabled")
        private val KEY_BACKUP_HOUR = intPreferencesKey("backup_hour")
        private val KEY_BACKUP_MINUTE = intPreferencesKey("backup_minute")
        private val KEY_DROPBOX_CREDENTIAL = stringPreferencesKey("dropbox_credential")
        private val KEY_DROPBOX_ACCOUNT = stringPreferencesKey("dropbox_account")
        private val KEY_LAST_LOCAL_CHANGE = longPreferencesKey("last_local_change_ms")
        private val KEY_LAST_SYNC = longPreferencesKey("last_sync_ms")
        private const val WORK_NAME_BACKUP = "daily_database_backup"
        private const val EXCEL_TEMPLATE_FILENAME = "Note_spese_2026_custom_template.xlsx"
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

    /** Email of the connected Dropbox account, empty when Dropbox is not connected. */
    val dropboxAccountFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DROPBOX_ACCOUNT] ?: ""
    }

    /** Serialized Dropbox OAuth credential (includes the refresh token), empty when not connected. */
    suspend fun getDropboxCredential(): String =
        context.dataStore.data.first()[KEY_DROPBOX_CREDENTIAL] ?: ""

    suspend fun saveDropboxCredential(credentialJson: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DROPBOX_CREDENTIAL] = credentialJson
        }
    }

    suspend fun saveDropboxAccount(email: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DROPBOX_ACCOUNT] = email.trim()
        }
    }

    /**
     * When the expenses were last changed on this device and when they were last aligned with
     * Dropbox (backup uploaded or backup restored). Used to warn before a risky restore.
     */
    val syncStateFlow: Flow<SyncState> = context.dataStore.data.map { prefs ->
        SyncState(
            lastLocalChangeMs = prefs[KEY_LAST_LOCAL_CHANGE] ?: 0L,
            lastSyncMs = prefs[KEY_LAST_SYNC] ?: 0L
        )
    }

    suspend fun getSyncState(): SyncState = syncStateFlow.first()

    /** To be called after every change to the expenses made on this device. */
    suspend fun markLocalChange() {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_LOCAL_CHANGE] = System.currentTimeMillis()
        }
    }

    /**
     * To be called after a backup has been uploaded or restored. [atMs] is the moment the data
     * was read (backup) or replaced (restore): changes made after it are still "unsaved".
     */
    suspend fun markSynced(atMs: Long = System.currentTimeMillis()) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_SYNC] = atMs
        }
    }

    /**
     * Name of this device as shown in the backup file names (e.g. "Pixel-8"):
     * the name set in Android settings, or the model when it is not available.
     */
    fun deviceName(): String {
        val name = try {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        } catch (e: Exception) {
            null
        }
        return sanitizeDeviceName(name?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Android")
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

    /**
     * Called at app start: backup is enabled by default, but without this the periodic
     * work would never be enqueued until the user touched the backup settings.
     * KEEP leaves an already scheduled job untouched.
     */
    suspend fun ensureDailyBackupScheduled() {
        if (backupEnabledFlow.first()) {
            scheduleDailyBackup(backupHourFlow.first(), backupMinuteFlow.first(), ExistingPeriodicWorkPolicy.KEEP)
        }
    }

    private fun scheduleDailyBackup(
        hour: Int,
        minute: Int,
        policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
    ) {
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
            policy,
            backupWorkRequest
        )
    }

    private fun cancelDailyBackup() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_BACKUP)
    }

    /**
     * Copies the Excel template chosen by the user into the app storage.
     * Returns false if the file cannot be read or is not an .xlsx file (a ZIP archive).
     */
    suspend fun saveExcelTemplate(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null || bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) {
                return@withContext false
            }
            File(context.filesDir, EXCEL_TEMPLATE_FILENAME).writeBytes(bytes)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun getExcelTemplateFile(): File? {
        val file = File(context.filesDir, EXCEL_TEMPLATE_FILENAME)
        return if (file.exists()) file else null
    }

    fun hasCustomExcelTemplate(): Boolean {
        return getExcelTemplateFile() != null
    }
}

data class SyncState(val lastLocalChangeMs: Long, val lastSyncMs: Long) {
    /** True when some changes made on this device are not in any backup yet. */
    val hasUnsavedChanges: Boolean get() = lastLocalChangeMs > lastSyncMs
}

