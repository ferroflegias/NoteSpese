package com.expensepereport.app.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.expensepereport.app.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Stores expense attachments on Dropbox when an account is connected,
 * otherwise (or when the upload fails) in the app's private storage.
 */
class AttachmentRepository(
    private val context: Context,
    private val dropboxService: DropboxService
) {

    companion object {
        const val LOCAL_ATTACHMENTS_FOLDER = "NoteSpese_Allegati"
        private const val VIEW_CACHE_FOLDER = "allegati_view"
        const val CROPPED_FILE_PREFIX = "cropped_rec_"
    }

    /** Returns the saved attachment path, or null if the file could not be read or stored. */
    suspend fun saveAttachment(uri: Uri, isPdf: Boolean, dataSpesa: String): String? {
        val fileBytes = withContext(Dispatchers.IO) {
            try {
                // Photos are resized before upload: a full-resolution camera shot is 5-10 MB.
                // Photos from the crop dialog are already sized and encoded: no second JPEG pass.
                val alreadyCropped = uri.scheme == "file" &&
                    uri.lastPathSegment?.startsWith(CROPPED_FILE_PREFIX) == true
                val compressed = if (isPdf || alreadyCropped) null else ImageUtils.compressImageForAttachment(context, uri)
                compressed ?: context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        } ?: return null

        val ext = if (isPdf) "pdf" else "jpg"

        if (dropboxService.isConnected()) {
            dropboxService.uploadAttachment(fileBytes, dataSpesa, ext)?.let { return it }
        }

        return saveLocally(fileBytes, dataSpesa, ext)
    }

    suspend fun deleteAttachment(path: String?) {
        if (path.isNullOrBlank()) return
        if (DropboxService.isDropboxPath(path)) {
            dropboxService.deleteFile(path)
            return
        }
        withContext(Dispatchers.IO) {
            try {
                val file = File(path.removePrefix("file://"))
                if (file.isAbsolute && file.exists()) file.delete()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /** Reads an attachment stored either locally or on Dropbox. */
    suspend fun readAttachmentBytes(path: String): ByteArray? {
        if (DropboxService.isDropboxPath(path)) {
            return dropboxService.downloadFileBytes(path)
        }
        val localFile = File(path.removePrefix("file://"))
        if (localFile.isAbsolute && localFile.exists()) {
            return withContext(Dispatchers.IO) { localFile.readBytes() }
        }
        // Old Google Drive links can only be opened in the browser
        return null
    }

    /**
     * Returns a content:// Uri to open the attachment with another app (downloading it first
     * when it is on Dropbox), or null when it cannot be read.
     */
    suspend fun getViewableUri(path: String): Uri? {
        val file = if (DropboxService.isDropboxPath(path)) {
            val bytes = dropboxService.downloadFileBytes(path) ?: return null
            withContext(Dispatchers.IO) {
                try {
                    val folder = File(context.cacheDir, VIEW_CACHE_FOLDER).apply { mkdirs() }
                    File(folder, path.substringAfterLast('/')).apply { writeBytes(bytes) }
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            } ?: return null
        } else {
            File(path.removePrefix("file://")).takeIf { it.isAbsolute && it.exists() } ?: return null
        }
        return try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private suspend fun saveLocally(fileBytes: ByteArray, dataSpesa: String, ext: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val year = if (dataSpesa.length >= 4) dataSpesa.substring(0, 4) else "2026"
                val month = if (dataSpesa.length >= 7) dataSpesa.substring(5, 7) else "01"
                val localFolder = File(context.filesDir, "$LOCAL_ATTACHMENTS_FOLDER/$year/$month")
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
