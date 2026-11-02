package com.expensepereport.app.data

import android.content.Context
import android.net.Uri
import com.expensepereport.app.util.ImageUtils
import com.expensepereport.app.util.contentUriFor
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
        private const val LOCAL_ATTACHMENTS_FOLDER = "NoteSpese_Allegati"
        private const val VIEW_CACHE_FOLDER = "allegati_view"

        /** Prefixes of the temporary photos written to the cache by the camera and the crop dialog. */
        const val CAMERA_FILE_PREFIX = "camera_photo_"
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
                localFile(path)?.delete()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /** Reads an attachment stored either locally or on Dropbox. Old Google Drive links return null. */
    suspend fun readAttachmentBytes(path: String): ByteArray? {
        if (DropboxService.isDropboxPath(path)) {
            return dropboxService.downloadFileBytes(path)
        }
        val file = localFile(path) ?: return null
        return withContext(Dispatchers.IO) { file.readBytes() }
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
            localFile(path) ?: return null
        }
        return try {
            context.contentUriFor(file)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** True for an attachment saved only in the private storage of this device. */
    fun isLocalOnly(path: String?): Boolean =
        !path.isNullOrBlank() && !DropboxService.isDropboxPath(path) && localFile(path) != null

    /**
     * Uploads to Dropbox the attachments saved only on this device (e.g. taken while Dropbox was
     * not connected), updates the expenses with the new Dropbox path and deletes the local copy.
     */
    suspend fun uploadLocalAttachments(spesaRepository: SpesaRepository): LocalUploadResult {
        if (!dropboxService.isConnected()) return LocalUploadResult(0, 0)
        var uploaded = 0
        var failed = 0
        spesaRepository.getSpese().filter { isLocalOnly(it.allegatoPath) }.forEach { spesa ->
            val file = localFile(spesa.allegatoPath!!) ?: return@forEach
            val ext = file.extension.lowercase().ifBlank { "jpg" }
            val bytes = withContext(Dispatchers.IO) {
                try {
                    file.readBytes()
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
            val newPath = bytes?.let { dropboxService.uploadAttachment(it, spesa.data, ext) }
            if (newPath == null) {
                failed++
                return@forEach
            }
            try {
                spesaRepository.updateSpesa(spesa.copy(allegatoPath = newPath))
                withContext(Dispatchers.IO) { file.delete() }
                uploaded++
            } catch (e: Exception) {
                e.printStackTrace()
                dropboxService.deleteFile(newPath)
                failed++
            }
        }
        return LocalUploadResult(uploaded, failed)
    }

    /**
     * Deletes the temporary photos taken with the camera or produced by the crop dialog:
     * once the expense is saved they have been copied (to Dropbox or to the private storage).
     */
    suspend fun clearTemporaryPhotos() = withContext(Dispatchers.IO) {
        context.cacheDir.listFiles()
            ?.filter { it.name.startsWith(CAMERA_FILE_PREFIX) || it.name.startsWith(CROPPED_FILE_PREFIX) }
            ?.forEach { it.delete() }
    }

    /** The local file of an attachment saved on the device, or null if it does not exist. */
    private fun localFile(path: String): File? =
        File(path.removePrefix("file://")).takeIf { it.isAbsolute && it.exists() }

    private suspend fun saveLocally(fileBytes: ByteArray, dataSpesa: String, ext: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val (year, month) = attachmentFolderParts(dataSpesa)
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

data class LocalUploadResult(val uploaded: Int, val failed: Int)
