/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.coil

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.disk.DiskCache
import coil.fetch.SourceResult
import coil.request.Options
import coil.request.SuccessResult
import coil.size.Dimension
import coil.size.Size
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.file.lastModifiedInstant
import me.zhanghai.android.files.filelist.isRemotePath

/**
 * Small encoded copies of remote thumbnails in Coil's disk cache.
 *
 * Coil 2 only fills its disk cache from its own HTTP fetcher. [PathAttributesFetcher] never used
 * it, so the "Keep thumbnails of remote files" setting stored nothing, and every thumbnail of a
 * remote file was read in full again once it fell out of the memory cache or the app restarted.
 *
 * The copy is the decoded thumbnail, not the original: a few dozen KiB instead of several MiB.
 * It is keyed by path, modification time, file size and requested pixel size, so a changed file
 * or a different cell size misses and the old entry ages out of the LRU. The decoded bitmap is
 * already rotated, and the copy carries no EXIF, so it is not rotated twice.
 */
@OptIn(ExperimentalCoilApi::class)
internal object RemoteThumbnailDiskCache {
    // Bump when the stored format changes.
    private const val VERSION = 1
    private const val QUALITY = 85
    const val MIME_TYPE = "image/webp"

    /** Pixel size of a thumbnail request, or null for an unsized or larger one. */
    fun thumbnailSize(size: Size): Pair<Int, Int>? {
        val width = size.width
        val height = size.height
        // @see android.provider.MediaStore.ThumbnailConstants.MINI_SIZE
        if (width !is Dimension.Pixels || height !is Dimension.Pixels) {
            return null
        }
        if (width.px > 512 || height.px > 384) {
            return null
        }
        return width.px to height.px
    }

    fun key(path: Path, attributes: BasicFileAttributes, width: Int, height: Int): String =
        key(path.toString(), attributes.lastModifiedInstant.toEpochMilli(), attributes.size(),
            width, height)

    fun key(path: String, lastModifiedMillis: Long, size: Long, width: Int, height: Int): String =
        "remote-thumbnail:v$VERSION:$lastModifiedMillis:$size:${width}x$height:$path"

    /** A stored thumbnail for [key], or null. The result owns the snapshot until Coil closes it. */
    fun read(diskCache: DiskCache, key: String): SourceResult? {
        val snapshot = diskCache.openSnapshot(key) ?: return null
        return SourceResult(
            ImageSource(snapshot.data, diskCache.fileSystem, key, snapshot), MIME_TYPE,
            DataSource.DISK
        )
    }

    /**
     * The stored thumbnail of [path] at this pixel size, or null if there is none or the request
     * may not read the disk cache.
     */
    fun readStored(
        path: Path,
        attributes: BasicFileAttributes,
        width: Int,
        height: Int,
        options: Options,
        imageLoader: ImageLoader
    ): SourceResult? {
        if (!options.diskCachePolicy.readEnabled) {
            return null
        }
        val diskCache = imageLoader.diskCache ?: return null
        return read(diskCache, key(path, attributes, width, height))
    }

    /** @return false if another write for [key] is in progress or encoding failed. */
    fun write(diskCache: DiskCache, key: String, bitmap: Bitmap): Boolean {
        val editor = diskCache.openEditor(key) ?: return false
        var committed = false
        // A hardware bitmap cannot be encoded directly.
        val softwareBitmap = if (bitmap.config?.isHardware == true) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }
        try {
            if (softwareBitmap == null) {
                return false
            }
            val encoded = diskCache.fileSystem.write(editor.data) {
                softwareBitmap.compress(compressFormat, QUALITY, outputStream())
            }
            if (encoded) {
                editor.commit()
                committed = true
            }
            return encoded
        } finally {
            if (!committed) {
                editor.abort()
            }
            if (softwareBitmap != null && softwareBitmap !== bitmap) {
                softwareBitmap.recycle()
            }
        }
    }

    /**
     * The key and bitmap to store for [result], or null when it must not be stored: a local
     * file, not a thumbnail size, writes turned off, or already from the memory or disk cache.
     */
    fun entryToWrite(result: SuccessResult, size: Size): Pair<String, Bitmap>? {
        val request = result.request
        if (!request.diskCachePolicy.writeEnabled) {
            return null
        }
        if (result.dataSource == DataSource.MEMORY_CACHE || result.diskCacheKey != null) {
            return null
        }
        val data = request.data as? Pair<*, *> ?: return null
        val path = data.first as? Path ?: return null
        val attributes = data.second as? BasicFileAttributes ?: return null
        if (!path.isRemotePath) {
            return null
        }
        val (width, height) = thumbnailSize(size) ?: return null
        // An animated drawable has no single bitmap to store.
        val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return null
        return key(path, attributes, width, height) to bitmap
    }

    @Suppress("DEPRECATION")
    private val compressFormat: Bitmap.CompressFormat
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            // Lossy below quality 100.
            Bitmap.CompressFormat.WEBP
        }
}
