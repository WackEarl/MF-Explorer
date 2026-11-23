/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import android.graphics.BitmapFactory
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.asMimeType
import me.zhanghai.android.files.file.asMimeTypeOrNull
import me.zhanghai.android.files.provider.common.AndroidFileTypeDetector
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.readAttributes
import okio.BufferedSource
import okio.buffer
import okio.source
import java.io.IOException

internal class ImageInfo(
    val attributes: BasicFileAttributes,
    val width: Int,
    val height: Int,
    val mimeType: MimeType,
    val largeImageOrientation: LargeImageOrientation
) {
    val shouldUseLargeImageView: Boolean
        get() {
            // See BitmapFactory.cpp encodedFormatToString()
            if (mimeType == MimeType.IMAGE_GIF) {
                return false
            }
            if (width <= 0 || height <= 0) {
                return false
            }
            // 4 bytes per pixel for ARGB_8888.
            if (width.toLong() * height * 4 > MAX_BITMAP_SIZE) {
                return true
            }
            if (width > 2048 || height > 2048) {
                val ratio = width.toFloat() / height
                if (ratio < 0.5 || ratio > 2) {
                    return true
                }
            }
            return false
        }

    companion object {
        // @see android.graphics.RecordingCanvas#MAX_BITMAP_SIZE
        private const val MAX_BITMAP_SIZE = 100 * 1024 * 1024
    }
}

/** Reads the bounds and, for the large image view, the EXIF orientation of [this]. */
@Throws(IOException::class)
internal fun Path.loadImageInfo(): ImageInfo {
    val attributes = readAttributes(BasicFileAttributes::class.java)
    val mimeType = AndroidFileTypeDetector.getMimeType(this, attributes).asMimeType()
    return readImageInfo(attributes, mimeType) { newInputStream().source().buffer() }
}

/**
 * A JPEG keeps both the frame header and EXIF at its start, so it is opened once and both reads
 * peek at the same source: the header is fetched once and only as far as the decoders need it.
 *
 * Other formats are opened once per read and never peeked at. A WebP may keep its EXIF at the
 * end of the file, and a peeking read there would hold the whole file in memory.
 */
@Throws(IOException::class)
internal fun readImageInfo(
    attributes: BasicFileAttributes,
    mimeType: MimeType,
    open: () -> BufferedSource
): ImageInfo {
    if (mimeType == MimeType.IMAGE_JPEG) {
        return open().use { source ->
            readImageInfo(attributes, mimeType, { source.peek() }, { source.peek() })
        }
    }
    return readImageInfo(attributes, mimeType, open, open)
}

private fun readImageInfo(
    attributes: BasicFileAttributes,
    mimeType: MimeType,
    openForBounds: () -> BufferedSource,
    openForOrientation: () -> BufferedSource
): ImageInfo {
    val bitmapOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openForBounds().use { BitmapFactory.decodeStream(it.inputStream(), null, bitmapOptions) }
    val width = bitmapOptions.outWidth
    val height = bitmapOptions.outHeight
    val actualMimeType = bitmapOptions.outMimeType?.asMimeTypeOrNull() ?: mimeType
    val withoutOrientation = ImageInfo(
        attributes, width, height, actualMimeType, LargeImageOrientation.NORMAL
    )
    // Coil applies EXIF for the normal view. Only the large view needs it read here.
    if (!withoutOrientation.shouldUseLargeImageView) {
        return withoutOrientation
    }
    val orientation = try {
        openForOrientation().use {
            LargeImageOrientation.read(it.inputStream(), bitmapOptions.outMimeType)
        }
    } catch (e: Exception) {
        e.printStackTrace()
        LargeImageOrientation.NORMAL
    }
    return ImageInfo(attributes, width, height, actualMimeType, orientation)
}
