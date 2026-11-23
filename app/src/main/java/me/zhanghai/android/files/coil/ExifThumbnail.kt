/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.coil

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import coil.decode.DecodeUtils
import coil.size.Scale
import okio.BufferedSource
import java.io.FilterInputStream
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The thumbnail a camera embeds in a JPEG's EXIF data, which sits in the first few KiB of the
 * file. Decoding the image itself for a thumbnail reads the whole file, which on NFS is tens of
 * MiB per camera photo.
 */
internal object ExifThumbnail {
    private const val TAG = "ExifThumbnail"

    /**
     * At most this much of the file is read to find the thumbnail. EXIF lives in one APP1
     * segment of at most 64 KiB, but other segments may come first.
     */
    const val MAX_HEADER_BYTES = 256 * 1024

    /** A thumbnail within this much of the image's aspect ratio is used as it is. */
    const val MAX_ASPECT_RATIO_DIFFERENCE = 0.02

    /**
     * Cameras pad the thumbnail of a 3:2 photo to 160x120 with black bars. A bar pixel is at
     * most this bright, which leaves room for JPEG noise.
     */
    private const val MAX_BAR_LUMINANCE = 48

    /** Black rows or columns next to the bars that are cut off as well. */
    private const val MAX_EXTRA_BAR_PIXELS = 2

    /**
     * Decodes the embedded thumbnail of the JPEG in [source] for a [width] x [height] request,
     * with any black bars cut off and the image's EXIF orientation applied, or returns null if
     * there is none or it does not show the whole image. Any size is used: a small thumbnail
     * is scaled up when displayed, which is far cheaper than reading the whole file.
     *
     * Only peeks at [source], so on null it can still be decoded in full, and the bytes read
     * here are not read again.
     */
    fun decode(source: BufferedSource, width: Int, height: Int, scale: Scale): Bitmap? {
        val exif = try {
            ExifInterface(LimitedInputStream(source.peek().inputStream(), MAX_HEADER_BYTES))
        } catch (e: Exception) {
            // Not an error: the image is decoded in full instead.
            Log.d(TAG, "Cannot read EXIF", e)
            return null
        }
        val bytes = exif.thumbnailBytes ?: return null
        if (!exif.isThumbnailCompressed) {
            return null
        }
        // As stored, before the EXIF orientation: the thumbnail is stored the same way.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        // The primary image size comes from the JPEG frame header, which precedes the pixels. If
        // other segments push it past the read limit, the size EXIF records is the next best.
        val imageWidth = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
            .takeIf { it > 0 } ?: exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0)
        val imageHeight = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
            .takeIf { it > 0 } ?: exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0)
        val content = contentRect(bounds.outWidth, bounds.outHeight, imageWidth, imageHeight)
            ?: return null
        val isRotated = exif.rotationDegrees == 90 || exif.rotationDegrees == 270
        val options = BitmapFactory.Options().apply {
            inSampleSize = DecodeUtils.calculateInSampleSize(
                if (isRotated) content.height() else content.width(),
                if (isRotated) content.width() else content.height(),
                width, height, scale
            )
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val sampled = Rect(
            content.left / options.inSampleSize, content.top / options.inSampleSize,
            content.right / options.inSampleSize, content.bottom / options.inSampleSize
        )
        val cropped = bitmap.cropBars(sampled) ?: run {
            // Not black: this thumbnail shows something other than the whole image.
            bitmap.recycle()
            return null
        }
        return cropped.withExifOrientation(exif.rotationDegrees, exif.isFlipped)
    }

    /**
     * The part of a [thumbnailWidth] x [thumbnailHeight] thumbnail that shows the whole
     * [imageWidth] x [imageHeight] image, all as stored: the whole thumbnail, or its center if
     * it was padded to another aspect ratio. Null if a size is unknown.
     */
    internal fun contentRect(
        thumbnailWidth: Int,
        thumbnailHeight: Int,
        imageWidth: Int,
        imageHeight: Int
    ): Rect? {
        if (thumbnailWidth <= 0 || thumbnailHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) {
            return null
        }
        val thumbnailRatio = thumbnailWidth.toDouble() / thumbnailHeight
        val imageRatio = imageWidth.toDouble() / imageHeight
        if (abs(thumbnailRatio - imageRatio) / imageRatio <= MAX_ASPECT_RATIO_DIFFERENCE) {
            return Rect(0, 0, thumbnailWidth, thumbnailHeight)
        }
        return if (imageRatio > thumbnailRatio) {
            // Bars above and below.
            val contentHeight = (thumbnailWidth / imageRatio).roundToInt().coerceAtLeast(1)
            val top = (thumbnailHeight - contentHeight) / 2
            Rect(0, top, thumbnailWidth, top + contentHeight)
        } else {
            // Bars left and right.
            val contentWidth = (thumbnailHeight * imageRatio).roundToInt().coerceAtLeast(1)
            val left = (thumbnailWidth - contentWidth) / 2
            Rect(left, 0, left + contentWidth, thumbnailHeight)
        }
    }

    /**
     * This bitmap cut to [content], or null if what lies outside is not black bars. The row or
     * column right next to [content] is not checked: JPEG blurs the edge into it.
     */
    private fun Bitmap.cropBars(content: Rect): Bitmap? {
        if (content.left == 0 && content.top == 0 && content.right >= width &&
            content.bottom >= height) {
            return this
        }
        val right = content.right.coerceAtMost(width)
        val bottom = content.bottom.coerceAtMost(height)
        val bars = listOf(
            Rect(0, 0, width, content.top - 1),
            Rect(0, bottom + 1, width, height),
            Rect(0, 0, content.left - 1, height),
            Rect(right + 1, 0, width, height)
        )
        if (bars.any { !it.isEmpty && !isDark(it.left, it.top, it.right, it.bottom) }) {
            return null
        }
        // The bars of a camera may be a pixel wider than the exact aspect ratio gives.
        var cropTop = content.top
        var cropBottom = bottom
        var cropLeft = content.left
        var cropRight = right
        repeat(MAX_EXTRA_BAR_PIXELS) {
            if (cropTop > 0 && cropBottom - cropTop > 1 &&
                isDark(cropLeft, cropTop, cropRight, cropTop + 1)) {
                cropTop++
            }
            if (bottom < height && cropBottom - cropTop > 1 &&
                isDark(cropLeft, cropBottom - 1, cropRight, cropBottom)) {
                cropBottom--
            }
            if (cropLeft > 0 && cropRight - cropLeft > 1 &&
                isDark(cropLeft, cropTop, cropLeft + 1, cropBottom)) {
                cropLeft++
            }
            if (right < width && cropRight - cropLeft > 1 &&
                isDark(cropRight - 1, cropTop, cropRight, cropBottom)) {
                cropRight--
            }
        }
        val cropped = Bitmap.createBitmap(
            this, cropLeft, cropTop, cropRight - cropLeft, cropBottom - cropTop
        )
        if (cropped !== this) {
            recycle()
        }
        return cropped
    }

    private fun Bitmap.isDark(left: Int, top: Int, right: Int, bottom: Int): Boolean {
        val width = right - left
        val pixels = IntArray(width * (bottom - top))
        getPixels(pixels, 0, width, left, top, width, bottom - top)
        return pixels.all { it.luminance <= MAX_BAR_LUMINANCE }
    }

    private val Int.luminance: Int
        get() = (299 * Color.red(this) + 587 * Color.green(this) + 114 * Color.blue(this)) / 1000

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
