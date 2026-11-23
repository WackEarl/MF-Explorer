/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import android.graphics.Bitmap
import android.graphics.Matrix
import coil.memory.MemoryCache
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.coil.PathAttributesKeyer

/**
 * The file list's thumbnail of an image, shown while the viewer loads the full image. Only what
 * is already in Coil's memory cache is used; the file itself is never read for it.
 */
internal object ThumbnailPlaceholder {
    /** The key the file list's request stored its thumbnail under. */
    fun memoryCacheKey(path: Path, attributes: BasicFileAttributes): MemoryCache.Key =
        MemoryCache.Key(PathAttributesKeyer.key(path, attributes))

    /**
     * [displayed] has the EXIF orientation applied, as Coil decodes it. The large image view
     * rotates its preview like the image and mirrors the whole view, so it needs the stored
     * pixel orientation back: mirror first, then rotate back.
     *
     * @return [displayed] itself if nothing needs to change, otherwise a new bitmap.
     */
    fun toStoredOrientation(displayed: Bitmap, orientation: LargeImageOrientation): Bitmap {
        if (orientation.rotation == 0 && !orientation.isMirrored) {
            return displayed
        }
        val matrix = Matrix().apply {
            if (orientation.isMirrored) {
                postScale(-1f, 1f)
            }
            postRotate(-orientation.rotation.toFloat())
        }
        // A hardware bitmap cannot be the source of a transformed copy on every API level.
        val source = if (displayed.config == Bitmap.Config.HARDWARE) {
            displayed.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            displayed
        }
        val stored = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (source !== displayed && source !== stored) {
            source.recycle()
        }
        return stored
    }
}
