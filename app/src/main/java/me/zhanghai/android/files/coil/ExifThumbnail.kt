/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.coil

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import coil.decode.DecodeUtils
import coil.size.Scale
import okio.BufferedSource
import java.io.FilterInputStream
import java.io.InputStream
import kotlin.math.abs

/**
 * The thumbnail a camera embeds in a JPEG's EXIF data, which sits in the first few KiB of the
 * file. Decoding the image itself for a thumbnail reads the whole file, which on NFS is tens of
 * MiB per camera photo.
 */
internal object ExifThumbnail {
    /**
     * At most this much of the file is read to find the thumbnail. EXIF lives in one APP1
     * segment of at most 64 KiB, but other segments may come first.
     */
    const val MAX_HEADER_BYTES = 256 * 1024

    /** A thumbnail is used if it needs at most this much scaling up, i.e. half the size. */
    const val MAX_UPSCALE = 2.0

    /** Thumbnails padded to another aspect ratio, e.g. 160x120 for 3:2, are not used. */
    const val MAX_ASPECT_RATIO_DIFFERENCE = 0.02

    /**
     * Decodes the embedded thumbnail of the JPEG in [source] for a [width] x [height] request,
     * with the image's EXIF orientation applied, or returns null if there is none that fits.
     *
     * Only peeks at [source], so on null it can still be decoded in full, and the bytes read
     * here are not read again.
     */
    fun decode(source: BufferedSource, width: Int, height: Int, scale: Scale): Bitmap? {
        val exif = try {
            ExifInterface(LimitedInputStream(source.peek().inputStream(), MAX_HEADER_BYTES))
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
        val bytes = exif.thumbnailBytes ?: return null
        if (!exif.isThumbnailCompressed) {
            return null
        }
        val isRotated = exif.rotationDegrees == 90 || exif.rotationDegrees == 270
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val thumbnailWidth = if (isRotated) bounds.outHeight else bounds.outWidth
        val thumbnailHeight = if (isRotated) bounds.outWidth else bounds.outHeight
        // The primary image size comes from the JPEG frame header, which precedes the pixels.
        val storedImageWidth = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
        val storedImageHeight = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
        val imageWidth = if (isRotated) storedImageHeight else storedImageWidth
        val imageHeight = if (isRotated) storedImageWidth else storedImageHeight
        if (!isUsable(thumbnailWidth, thumbnailHeight, imageWidth, imageHeight, width, height,
                scale)) {
            return null
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = DecodeUtils.calculateInSampleSize(
                thumbnailWidth, thumbnailHeight, width, height, scale
            )
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return bitmap.withExifOrientation(exif.rotationDegrees, exif.isFlipped)
    }

    /** All sizes as displayed, i.e. with the EXIF rotation applied. */
    internal fun isUsable(
        thumbnailWidth: Int,
        thumbnailHeight: Int,
        imageWidth: Int,
        imageHeight: Int,
        width: Int,
        height: Int,
        scale: Scale
    ): Boolean {
        if (thumbnailWidth <= 0 || thumbnailHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) {
            return false
        }
        val thumbnailRatio = thumbnailWidth.toDouble() / thumbnailHeight
        val imageRatio = imageWidth.toDouble() / imageHeight
        if (abs(thumbnailRatio - imageRatio) / imageRatio > MAX_ASPECT_RATIO_DIFFERENCE) {
            return false
        }
        val multiplier = DecodeUtils.computeSizeMultiplier(
            thumbnailWidth, thumbnailHeight, width, height, scale
        )
        return multiplier <= MAX_UPSCALE
    }

    /** As Coil applies EXIF orientation: flip first, then rotate clockwise. */
    private fun Bitmap.withExifOrientation(rotationDegrees: Int, isFlipped: Boolean): Bitmap {
        if (rotationDegrees == 0 && !isFlipped) {
            return this
        }
        val matrix = Matrix().apply {
            if (isFlipped) {
                postScale(-1f, 1f)
            }
            postRotate(rotationDegrees.toFloat())
        }
        val oriented = Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
        if (oriented !== this) {
            recycle()
        }
        return oriented
    }

    /** Ends after [limit] bytes, so a file without EXIF cannot make the search read it all. */
    private class LimitedInputStream(
        inputStream: InputStream,
        private var remaining: Int
    ) : FilterInputStream(inputStream) {
        override fun read(): Int {
            if (remaining <= 0) {
                return -1
            }
            val byte = super.read()
            if (byte >= 0) {
                remaining--
            }
            return byte
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) {
                return -1
            }
            val read = super.read(b, off, len.coerceAtMost(remaining))
            if (read > 0) {
                remaining -= read
            }
            return read
        }

        override fun skip(n: Long): Long {
            val skipped = super.skip(n.coerceAtMost(remaining.toLong()))
            remaining -= skipped.toInt()
            return skipped
        }

        override fun available(): Int = super.available().coerceAtMost(remaining)

        override fun markSupported(): Boolean = false
    }
}
