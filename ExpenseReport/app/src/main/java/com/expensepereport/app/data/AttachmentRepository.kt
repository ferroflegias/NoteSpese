package com.expensepereport.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Stores expense attachments on Google Drive when an account is available,
 * otherwise (or when the upload fails) in the app's private storage.
 */
class AttachmentRepository(
    private val context: Context,
    private val settingsRepository: AppSettingsRepository,
    private val googleDriveService: GoogleDriveService
) {

    /** Returns the saved attachment path/URL, or null if the file could not be read or stored. */
    suspend fun saveAttachment(uri: Uri, isPdf: Boolean, dataSpesa: String): String? {
        val fileBytes = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        } ?: return null

        val ext = if (isPdf) "pdf" else "jpg"
        val mime = if (isPdf) "application/pdf" else "image/jpeg"

        val accountEmail = settingsRepository.getActiveAccountEmail()
        if (accountEmail.isNotBlank()) {
            googleDriveService.uploadAttachment(
                accountName = accountEmail,
                fileBytes = fileBytes,
                dataSpesa = dataSpesa,
                extension = ext,
                contentType = mime
            )?.let { return it }
        }

        return saveLocally(fileBytes, dataSpesa, ext)
    }

    suspend fun deleteAttachment(path: String?) {
        if (path.isNullOrBlank()) return
        googleDriveService.deleteFile(settingsRepository.getActiveAccountEmail(), path)
    }

    /** Reads an attachment stored either locally or on Google Drive. */
    suspend fun readAttachmentBytes(path: String): ByteArray? {
        val localFile = File(path.removePrefix("file://"))
        if (localFile.isAbsolute && localFile.exists()) {
            return withContext(Dispatchers.IO) { localFile.readBytes() }
        }
        return googleDriveService.downloadFileBytes(settingsRepository.getActiveAccountEmail(), path)
    }

    private suspend fun saveLocally(fileBytes: ByteArray, dataSpesa: String, ext: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val year = if (dataSpesa.length >= 4) dataSpesa.substring(0, 4) else "2026"
                val month = if (dataSpesa.length >= 7) dataSpesa.substring(5, 7) else "01"
                val localFolder = File(context.filesDir, "${GoogleDriveService.ATTACHMENTS_ROOT_FOLDER}/$year/$month")
                localFolder.mkdirs()
                val file = File(localFolder, "rec_${dataSpesa}_${System.currentTimeMillis()}.$ext")
                file.writeBytes(fileBytes)
                file.absolutePath
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
}
