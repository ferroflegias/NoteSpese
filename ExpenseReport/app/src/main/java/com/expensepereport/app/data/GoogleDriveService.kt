package com.expensepereport.app.data

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

class GoogleDriveService(private val context: Context) {

    companion object {
        const val ATTACHMENTS_ROOT_FOLDER = "NoteSpese_Allegati"
        const val BACKUPS_ROOT_FOLDER = "NoteSpese_Backups"

        fun getGoogleSignInOptions(): GoogleSignInOptions {
            return GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(Scope(DriveScopes.DRIVE_FILE))
                .build()
        }

        fun getLastSignedInAccount(context: Context): GoogleSignInAccount? {
            return GoogleSignIn.getLastSignedInAccount(context)
        }

        fun signOut(context: Context, onComplete: () -> Unit = {}) {
            val client = GoogleSignIn.getClient(context, getGoogleSignInOptions())
            client.signOut().addOnCompleteListener {
                onComplete()
            }
        }
    }

    fun getDriveServiceForAccountName(accountName: String): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            Collections.singleton(DriveScopes.DRIVE_FILE)
        )
        credential.selectedAccountName = accountName
        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName("PerExpenseReport")
            .build()
    }

    private fun getDriveService(account: GoogleSignInAccount): Drive {
        return getDriveServiceForAccountName(account.email ?: account.account?.name ?: "")
    }

    private fun getOrCreateFolder(drive: Drive, folderName: String, parentFolderId: String? = null): String {
        var query = "mimeType='application/vnd.google-apps.folder' and name='$folderName' and trashed=false"
        if (parentFolderId != null) {
            query += " and '$parentFolderId' in parents"
        } else {
            query += " and 'root' in parents"
        }

        val result = drive.files().list()
            .setQ(query)
            .setFields("files(id, name)")
            .execute()

        if (result.files != null && result.files.isNotEmpty()) {
            return result.files[0].id
        }

        val folderMetadata = File()
        folderMetadata.name = folderName
        folderMetadata.mimeType = "application/vnd.google-apps.folder"
        if (parentFolderId != null) {
            folderMetadata.parents = Collections.singletonList(parentFolderId)
        }

        val folder = drive.files().create(folderMetadata)
            .setFields("id")
            .execute()

        return folder.id
    }

    suspend fun uploadAttachment(
        accountName: String,
        fileBytes: ByteArray,
        dataSpesa: String,
        extension: String,
        contentType: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val drive = getDriveServiceForAccountName(accountName)

            val rootFolderId = getOrCreateFolder(drive, ATTACHMENTS_ROOT_FOLDER)

            val year = if (dataSpesa.length >= 4) dataSpesa.substring(0, 4) else "2026"
            val month = if (dataSpesa.length >= 7) dataSpesa.substring(5, 7) else "01"

            val yearFolderId = getOrCreateFolder(drive, year, rootFolderId)
            val monthFolderId = getOrCreateFolder(drive, month, yearFolderId)

            val nowStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "rec_${dataSpesa}_${nowStr}.${extension}"

            val fileMetadata = File()
            fileMetadata.name = filename
            fileMetadata.parents = Collections.singletonList(monthFolderId)

            val mediaContent = ByteArrayContent(contentType, fileBytes)
            val uploadedFile = drive.files().create(fileMetadata, mediaContent)
                .setFields("id, webViewLink, webContentLink")
                .execute()

            uploadedFile.webViewLink ?: uploadedFile.id
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun uploadAttachment(
        account: GoogleSignInAccount,
        fileBytes: ByteArray,
        dataSpesa: String,
        extension: String,
        contentType: String
    ): String? {
        return uploadAttachment(account.email ?: "", fileBytes, dataSpesa, extension, contentType)
    }

    suspend fun uploadDatabaseBackup(
        accountName: String,
        dbFile: java.io.File
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val drive = getDriveServiceForAccountName(accountName)
            val backupFolderId = getOrCreateFolder(drive, BACKUPS_ROOT_FOLDER)

            val nowStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "expense_report_backup_${nowStr}.db"

            val fileMetadata = File()
            fileMetadata.name = filename
            fileMetadata.parents = Collections.singletonList(backupFolderId)

            val fileBytes = dbFile.readBytes()
            val mediaContent = ByteArrayContent("application/x-sqlite3", fileBytes)

            drive.files().create(fileMetadata, mediaContent)
                .setFields("id")
                .execute()

            cleanupOldBackups(drive, backupFolderId)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun uploadDatabaseBackup(
        account: GoogleSignInAccount,
        dbFile: java.io.File
    ): Boolean {
        return uploadDatabaseBackup(account.email ?: "", dbFile)
    }

    private fun cleanupOldBackups(drive: Drive, backupFolderId: String) {
        try {
            val query = "'$backupFolderId' in parents and trashed=false"
            val result = drive.files().list()
                .setQ(query)
                .setFields("files(id, name, createdTime)")
                .execute()

            val thirtyDaysAgoMs = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)

            if (result.files != null) {
                for (file in result.files) {
                    val createdMs = file.createdTime?.value ?: continue
                    if (createdMs < thirtyDaysAgoMs) {
                        try {
                            drive.files().delete(file.id).execute()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun listBackups(accountName: String): List<File> = withContext(Dispatchers.IO) {
        try {
            val drive = getDriveServiceForAccountName(accountName)
            val backupFolderId = getOrCreateFolder(drive, BACKUPS_ROOT_FOLDER)
            val query = "'$backupFolderId' in parents and trashed=false"
            val result = drive.files().list()
                .setQ(query)
                .setFields("files(id, name, createdTime, size)")
                .setOrderBy("createdTime desc")
                .execute()
            result.files ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun downloadBackup(
        accountName: String,
        fileId: String,
        targetFile: java.io.File
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val drive = getDriveServiceForAccountName(accountName)
            val outputStream = FileOutputStream(targetFile)
            drive.files().get(fileId).executeMediaAndDownloadTo(outputStream)
            outputStream.flush()
            outputStream.close()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun getAttachmentsFolderUrl(accountName: String): String? = withContext(Dispatchers.IO) {
        try {
            val drive = getDriveServiceForAccountName(accountName)
            val folderId = getOrCreateFolder(drive, ATTACHMENTS_ROOT_FOLDER)
            "https://drive.google.com/drive/folders/$folderId"
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
