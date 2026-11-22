/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.coil

import coil.disk.DiskCache
import coil.intercept.Interceptor
import coil.request.CachePolicy
import coil.request.ImageResult
import coil.request.SuccessResult
import java8.nio.file.Path
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import me.zhanghai.android.files.filelist.isRemotePath
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat

/**
 * Coil keeps the decoded thumbnail in memory either way. This turns off the disk cache for a
 * remote file when the setting is off, and otherwise stores a freshly decoded remote thumbnail
 * there, which [PathAttributesFetcher] reads before it touches the remote file again.
 */
class RemoteImageDiskCacheInterceptor(
    private val diskCache: () -> DiskCache?
) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val path = chain.request.data.asPath()
        if (path != null && path.isRemotePath && !Settings.CACHE_REMOTE_FILES_ON_DISK.valueCompat) {
            val request = chain.request.newBuilder()
                .diskCachePolicy(CachePolicy.DISABLED)
                .build()
            return chain.proceed(request)
        }
        val result = chain.proceed(chain.request)
        if (path != null && result is SuccessResult) {
            RemoteThumbnailDiskCache.entryToWrite(result, chain.size)?.let { (key, bitmap) ->
                // Encoding is off the main thread and does not hold up showing the thumbnail.
                @OptIn(DelicateCoroutinesApi::class)
                GlobalScope.launch(Dispatchers.IO) {
                    val diskCache = diskCache() ?: return@launch
                    try {
                        RemoteThumbnailDiskCache.write(diskCache, key, bitmap)
                    } catch (e: Exception) {
                        // Only a cache. The thumbnail is read from the file again next time.
                        e.printStackTrace()
                    }
                }
            }
        }
        return result
    }

    private fun Any.asPath(): Path? =
        when (this) {
            is Path -> this
            is Pair<*, *> -> first as? Path
            else -> null
        }
}
