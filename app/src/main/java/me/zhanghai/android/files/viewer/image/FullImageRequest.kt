/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.size.Size
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.coil.PathAttributesKeyer

/**
 * The viewer's request for the full image, with the file list's thumbnail as its placeholder.
 *
 * The full image has its own memory cache key. Under the list's key, Coil would hand out the
 * cached thumbnail as the original size whenever it is not marked as sampled, which is the case
 * for a thumbnail decoded from the disk cache at its stored size.
 */
internal fun ImageRequest.Builder.fullImage(
    path: Path,
    attributes: BasicFileAttributes
): ImageRequest.Builder =
    data(path to attributes)
        .size(Size.ORIGINAL)
        .memoryCacheKey(fullImageMemoryCacheKey(path, attributes))
        .placeholderMemoryCacheKey(ThumbnailPlaceholder.memoryCacheKey(path, attributes))

internal fun fullImageMemoryCacheKey(path: Path, attributes: BasicFileAttributes): MemoryCache.Key =
    MemoryCache.Key(PathAttributesKeyer.key(path, attributes), mapOf(FULL_IMAGE_EXTRA to "true"))

private const val FULL_IMAGE_EXTRA = "mfx#full_image"
