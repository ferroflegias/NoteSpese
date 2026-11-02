package com.expensepereport.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream

object ImageUtils {

    /**
     * Longest side used for receipts: enough for OCR and printing, while keeping the
     * bitmap around 10-15 MB instead of 50+ MB for a modern camera photo.
     */
    const val MAX_IMAGE_DIMENSION = 2048

    /** JPEG quality used for saved attachments and cropped photos. */
    const val JPEG_QUALITY = 85

    /**
     * Decodes the image at [uri] applying its EXIF rotation. Very large photos are
     * subsampled so that the longest side stays below [maxDimension], avoiding OOM.
     */
    fun loadOrientedBitmap(context: Context, uri: Uri, maxDimension: Int = MAX_IMAGE_DIMENSION): Bitmap? {
        return try {
            val resolver = context.contentResolver

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
            }
            val bitmap = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return null

            val orientation = resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
            } ?: ExifInterface.ORIENTATION_UNDEFINED

            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> return bitmap
            }

            val matrix = Matrix().apply { postRotate(degrees) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            rotated
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Re-encodes a photo as a JPEG no larger than [MAX_IMAGE_DIMENSION], with the EXIF rotation
     * applied. Returns null if the image cannot be decoded. Call it off the main thread.
     */
    fun compressImageForAttachment(context: Context, uri: Uri): ByteArray? {
        val bitmap = loadOrientedBitmap(context, uri) ?: return null
        return try {
            ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
                output.toByteArray()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            bitmap.recycle()
        }
    }

    /** Smallest power of two that brings the longest side of the image down to [maxDimension] or less. */
    fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sampleSize = 1
        while (maxOf(width, height) / sampleSize > maxDimension) {
            sampleSize *= 2
        }
        return sampleSize
    }
}
