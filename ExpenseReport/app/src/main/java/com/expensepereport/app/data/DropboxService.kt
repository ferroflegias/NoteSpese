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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A database backup file stored in the app folder on Dropbox. */
data class BackupEntry(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val modified: Date?
)

/**
 * Stores attachments and database backups in the app's own folder on Dropbox
 * (Dropbox/Apps/<nome app>/), using OAuth 2 PKCE: no client secret, no SHA-1 fingerprint.
 *
 * Attachments are saved in the database as "dropbox:/Allegati/AAAA/MM/<file>".
 */
class DropboxService(
    private val context: Context,
    private val settingsRepository: AppSettingsRepository
) {

    companion object {
        const val PATH_PREFIX = "dropbox:"
        private const val ATTACHMENTS_FOLDER = "/Allegati"
        private const val BACKUPS_FOLDER = "/Backups"
        private const val BACKUP_RETENTION_MS = 30L * 24 * 60 * 60 * 1000

        private val SCOPES = listOf(
            "account_info.read",
            "files.metadata.read",
            "files.content.read",
            "files.content.write"
        )

        val appKey: String get() = BuildConfig.DROPBOX_APP_KEY

        /** False when the APK was built without DROPBOX_APP_KEY. */
        val isConfigured: Boolean get() = appKey.isNotBlank()

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
            val year = if (dataSpesa.length >= 4) dataSpesa.substring(0, 4) else "2026"
            val month = if (dataSpesa.length >= 7) dataSpesa.substring(5, 7) else "01"
            val nowStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val path = "$ATTACHMENTS_FOLDER/$year/$month/rec_${dataSpesa}_$nowStr.$extension"

            val metadata = fileBytes.inputStream().use { input ->
                client.files().uploadBuilder(path)
                    .withMode(WriteMode.ADD)
                    .withAutorename(true)
                    .uploadAndFinish(input)
            }
            PATH_PREFIX + metadata.pathDisplay
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun uploadDatabaseBackup(dbFile: File): Boolean = withContext(Dispatchers.IO) {
        val client = client() ?: return@withContext false
        try {
            val nowStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val path = "$BACKUPS_FOLDER/expense_report_backup_$nowStr.db"
            dbFile.inputStream().use { input ->
                client.files().uploadBuilder(path)
                    .withMode(WriteMode.ADD)
                    .withAutorename(true)
                    .uploadAndFinish(input)
            }
            cleanupOldBackups(client)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun listBackupFiles(client: DbxClientV2): List<FileMetadata> {
        val files = mutableListOf<FileMetadata>()
        try {
            var result = client.files().listFolder(BACKUPS_FOLDER)
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

    private fun cleanupOldBackups(client: DbxClientV2) {
        try {
            val threshold = System.currentTimeMillis() - BACKUP_RETENTION_MS
            listBackupFiles(client)
                .filter { it.serverModified.time < threshold }
                .forEach { file ->
                    try {
                        client.files().deleteV2(file.pathLower)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Backups sorted from the newest. */
    suspend fun listBackups(): List<BackupEntry> = withContext(Dispatchers.IO) {
        val client = client() ?: return@withContext emptyList()
        try {
            listBackupFiles(client)
                .sortedByDescending { it.serverModified }
                .map { BackupEntry(it.name, it.pathLower, it.size, it.serverModified) }
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
