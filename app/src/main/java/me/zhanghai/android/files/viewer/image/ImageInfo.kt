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

/** Opens [this] once for its bounds and, for the large image view, its EXIF orientation. */
@Throws(IOException::class)
internal fun Path.loadImageInfo(): ImageInfo {
    val attributes = readAttributes(BasicFileAttributes::class.java)
    val mimeType = AndroidFileTypeDetector.getMimeType(this, attributes).asMimeType()
    return newInputStream().source().buffer().use { readImageInfo(it, attributes, mimeType) }
}

/**
 * Both reads peek at [source], so the header bytes are fetched once and the EXIF read starts
 * at the beginning of the file again.
 */
internal fun readImageInfo(
    source: BufferedSource,
    attributes: BasicFileAttributes,
    mimeType: MimeType
): ImageInfo {
    val bitmapOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeStream(source.peek().inputStream(), null, bitmapOptions)
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
        LargeImageOrientation.read(source.peek().inputStream(), bitmapOptions.outMimeType)
    } catch (e: Exception) {
        e.printStackTrace()
        LargeImageOrientation.NORMAL
    }
    return ImageInfo(attributes, width, height, actualMimeType, orientation)
}
