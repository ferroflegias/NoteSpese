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
     * Longest side of saved attachments: sharp in the exported PDF even when zoomed in,
     * while avoiding the 50+ MB bitmaps of full-resolution camera photos.
     */
    const val ATTACHMENT_MAX_DIMENSION = 3200

    /** Longest side for on-screen preview (crop dialog) and OCR, where speed matters more. */
    const val PREVIEW_MAX_DIMENSION = 2048

    /** JPEG quality used for saved attachments and cropped photos. */
    const val JPEG_QUALITY = 92

    /**
     * Decodes the image at [uri] applying its EXIF rotation, with the longest side limited
     * to [maxDimension] to avoid huge bitmaps.
     *
     * With [exactSize] = false (preview, OCR) only fast power-of-two subsampling is used, so the
     * result may be up to half of [maxDimension]. With [exactSize] = true (saved attachments)
     * the image is decoded slightly larger and then scaled to exactly [maxDimension].
     */
    fun loadOrientedBitmap(context: Context, uri: Uri, maxDimension: Int, exactSize: Boolean = false): Bitmap? {
        return try {
            val resolver = context.contentResolver

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val longestSide = maxOf(bounds.outWidth, bounds.outHeight)
            val options = BitmapFactory.Options().apply {
                inSampleSize = if (exactSize) {
                    largestSampleSizeKeeping(longestSide, maxDimension)
                } else {
                    calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
                }
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
                else -> 0f
            }
            val decodedLongest = maxOf(bitmap.width, bitmap.height)
            val scale = if (decodedLongest > maxDimension) maxDimension.toFloat() / decodedLongest else 1f

            if (degrees == 0f && scale == 1f) return bitmap

            // Rotation and final scaling in a single pass
            val matrix = Matrix().apply {
                postScale(scale, scale)
                postRotate(degrees)
            }
            val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (transformed != bitmap) bitmap.recycle()
            transformed
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } catch (e: OutOfMemoryError) {
            e.printStackTrace()
            null
        }
    }

    /** Largest power of two that keeps the longest side at or above [minDimension]. */
    fun largestSampleSizeKeeping(longestSide: Int, minDimension: Int): Int {
        var sampleSize = 1
        while (longestSide / (sampleSize * 2) >= minDimension) {
            sampleSize *= 2
        }
        return sampleSize
    }

    /**
     * Re-encodes a photo as a JPEG no larger than [ATTACHMENT_MAX_DIMENSION], with the EXIF
     * rotation applied. Returns null if the image cannot be decoded. Call it off the main thread.
     */
    fun compressImageForAttachment(context: Context, uri: Uri): ByteArray? {
        val bitmap = loadOrientedBitmap(context, uri, ATTACHMENT_MAX_DIMENSION, exactSize = true) ?: return null
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
