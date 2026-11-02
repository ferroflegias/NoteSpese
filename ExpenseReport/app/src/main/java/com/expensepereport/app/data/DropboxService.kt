package com.expensepereport.app.data

import android.content.Context
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.android.Auth
import com.dropbox.core.oauth.DbxCredential
import com.dropbox.core.v2.DbxClientV2
import com.dropbox.core.v2.files.FileMetadata
import com.dropbox.core.v2.files.ListFolderErrorException
import com.dropbox.core.v2.files.WriteMode
import com.expensepereport.app.BuildConfig
import com.expensepereport.app.util.fileTimestamp
import com.expensepereport.app.util.sanitizeDeviceName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Date

/** A database backup file stored in the app folder on Dropbox. */
data class BackupEntry(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val modified: Date?,
    /** True for the safety copies taken automatically before a restore. */
    val isSafetyBackup: Boolean = false
) {
    /** Device that created the backup, read from the file name (null for older backups). */
    val deviceName: String? get() = DropboxService.deviceFromBackupName(name)
}

/**
 * Stores attachments and database backups in the app's own folder on Dropbox
 * (Dropbox/App/<nome app>/), using OAuth 2 PKCE: no client secret, no SHA-1 fingerprint.
 *
 * Attachments are saved in the database as "dropbox:/Allegati/AAAA/MM/<file>".
 */
class DropboxService(private val settingsRepository: AppSettingsRepository) {

    companion object {
        private const val PATH_PREFIX = "dropbox:"
        private const val ATTACHMENTS_FOLDER = "/Allegati"
        private const val BACKUPS_FOLDER = "/Backups"
        /**
         * Copies of the local data taken automatically before a restore. They live in their own
         * folder so that they never look like "the latest backup" to the other devices.
         */
        private const val SAFETY_BACKUPS_FOLDER = "/Backups_PreRipristino"
        private const val BACKUP_FILE_PREFIX = "expense_report_backup_"
        private val BACKUP_NAME_REGEX = Regex("^${BACKUP_FILE_PREFIX}\\d{8}_\\d{6}_(.+)\\.db$")
        private const val BACKUP_RETENTION_MS = 30L * 24 * 60 * 60 * 1000

        private val SCOPES = listOf(
            "account_info.read",
            "files.metadata.read",
            "files.content.read",
            "files.content.write"
        )

        private val appKey: String get() = BuildConfig.DROPBOX_APP_KEY

        /** False when the APK was built without DROPBOX_APP_KEY. */
        val isConfigured: Boolean get() = appKey.isNotBlank()

        /** Backup file name with timestamp and device, e.g. expense_report_backup_20260131_142500_Pixel-8.db */
        fun backupFileName(timestamp: String, deviceName: String): String =
            "$BACKUP_FILE_PREFIX${timestamp}_${sanitizeDeviceName(deviceName)}.db"

        fun deviceFromBackupName(name: String): String? =
            BACKUP_NAME_REGEX.find(name)?.groupValues?.get(1)

        fun isDropboxPath(path: String?): Boolean = path?.startsWith(PATH_PREFIX) == true

        private fun requestConfig(): DbxRequestConfig =
            DbxRequestConfig.newBuilder("PerExpenseReport").build()
    }

    /** Opens the Dropbox app (or the browser) to authorise access to the app folder. */
    fun startLogin(context: Context) {
        Auth.startOAuth2PKCE(context, appKey, requestConfig(), SCOPES)
    }

    /**
     * To be called when the screen that started [startLogin] is resumed: stores the credential
     * returned by Dropbox and returns the account email, or null when no login was pending.
     */
    suspend fun completeLoginIfPending(): String? {
        val credential = Auth.getDbxCredential() ?: return null
        settingsRepository.saveDropboxCredential(DbxCredential.Writer.writeToString(credential))
        val email = withContext(Dispatchers.IO) {
            try {
                DbxClientV2(requestConfig(), credential).users().currentAccount.email
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        } ?: "Account Dropbox"
        settingsRepository.saveDropboxAccount(email)
        return email
    }

    suspend fun isConnected(): Boolean = settingsRepository.getDropboxCredential().isNotBlank()

    /** Revokes the token on Dropbox (best effort) and forgets the stored credential. */
    suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            try {
                client()?.auth()?.tokenRevoke()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        settingsRepository.saveDropboxCredential("")
        settingsRepository.saveDropboxAccount("")
    }

    /** The client refreshes the short-lived access token by itself using the stored refresh token. */
    private suspend fun client(): DbxClientV2? {
        val json = settingsRepository.getDropboxCredential()
        if (json.isBlank()) return null
        return try {
            DbxClientV2(requestConfig(), DbxCredential.Reader.readFully(json))
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun uploadAttachment(
        fileBytes: ByteArray,
        dataSpesa: String,
        extension: String
    ): String? = withContext(Dispatchers.IO) {
        val client = client() ?: return@withContext null
        try {
            val (year, month) = attachmentFolderParts(dataSpesa)
            val path = "$ATTACHMENTS_FOLDER/$year/$month/rec_${dataSpesa}_${fileTimestamp()}.$extension"

            val metadata = fileBytes.inputStream().use { input -> upload(client, path, input) }
            PATH_PREFIX + (metadata.pathDisplay ?: path)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Uploads a backup of [dbFile] to the "Backups" folder. On success the data on this device
     * is marked as saved ([AppSettingsRepository.markSynced]).
     */
    suspend fun uploadDatabaseBackup(dbFile: File): Boolean {
        val startedAt = System.currentTimeMillis()
        val success = uploadBackupTo(BACKUPS_FOLDER, dbFile)
        if (success) settingsRepository.markSynced(startedAt)
        return success
    }

    /** Uploads a safety copy of the local data before it is replaced by a restore. */
    suspend fun uploadSafetyBackup(dbFile: File): Boolean = uploadBackupTo(SAFETY_BACKUPS_FOLDER, dbFile)

    private suspend fun uploadBackupTo(folder: String, dbFile: File): Boolean = withContext(Dispatchers.IO) {
        val client = client() ?: return@withContext false
        try {
            val path = "$folder/${backupFileName(fileTimestamp(), settingsRepository.deviceName())}"
            dbFile.inputStream().use { input -> upload(client, path, input) }
            cleanupOldBackups(client, folder)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /** Uploads a new file; an existing file with the same name is never overwritten. */
    private fun upload(client: DbxClientV2, path: String, input: InputStream): FileMetadata =
        client.files().uploadBuilder(path)
            .withMode(WriteMode.ADD)
            .withAutorename(true)
            .uploadAndFinish(input)

    private fun listBackupFiles(client: DbxClientV2, folder: String): List<FileMetadata> {
        val files = mutableListOf<FileMetadata>()
        try {
            var result = client.files().listFolder(folder)
            while (true) {
                files += result.entries.filterIsInstance<FileMetadata>()
                if (!result.hasMore) break
                result = client.files().listFolderContinue(result.cursor)
            }
        } catch (e: ListFolderErrorException) {
            // The folder does not exist until the first backup is uploaded
            if (!(e.errorValue.isPath && e.errorValue.pathValue.isNotFound)) throw e
        }
        return files
    }

    private fun cleanupOldBackups(client: DbxClientV2, folder: String) {
        try {
            val threshold = System.currentTimeMillis() - BACKUP_RETENTION_MS
            listBackupFiles(client, folder)
                .filter { it.serverModified.time < threshold }
                .forEach { file ->
                    val path = file.pathLower ?: return@forEach
                    try {
                        client.files().deleteV2(path)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Backups sorted from the newest: regular backups first, then the safety copies. */
    suspend fun listBackups(): List<BackupEntry> = withContext(Dispatchers.IO) {
        val client = client() ?: return@withContext emptyList()
        try {
            fun entries(folder: String, isSafety: Boolean) = listBackupFiles(client, folder)
                .sortedByDescending { it.serverModified }
                .mapNotNull { file ->
                    val path = file.pathLower ?: return@mapNotNull null
                    BackupEntry(file.name, path, file.size, file.serverModified, isSafety)
                }
            entries(BACKUPS_FOLDER, isSafety = false) + entries(SAFETY_BACKUPS_FOLDER, isSafety = true)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun downloadBackup(path: String, targetFile: File): Boolean = withContext(Dispatchers.IO) {
        val client = client() ?: return@withContext false
        try {
            FileOutputStream(targetFile).use { output ->
                client.files().download(path).download(output)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /** Downloads an attachment saved as "dropbox:/...". */
    suspend fun downloadFileBytes(storedPath: String): ByteArray? = withContext(Dispatchers.IO) {
        if (!isDropboxPath(storedPath)) return@withContext null
        val client = client() ?: return@withContext null
        try {
            ByteArrayOutputStream().use { output ->
                client.files().download(storedPath.removePrefix(PATH_PREFIX)).download(output)
                output.toByteArray()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun deleteFile(storedPath: String): Boolean = withContext(Dispatchers.IO) {
        if (!isDropboxPath(storedPath)) return@withContext false
        val client = client() ?: return@withContext false
        try {
            client.files().deleteV2(storedPath.removePrefix(PATH_PREFIX))
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
