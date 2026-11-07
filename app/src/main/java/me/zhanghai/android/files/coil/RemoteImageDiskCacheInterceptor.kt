/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.coil

import coil.intercept.Interceptor
import coil.request.CachePolicy
import coil.request.ImageResult
import java8.nio.file.Path
import me.zhanghai.android.files.filelist.isRemotePath
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat

/**
 * Coil keeps the decoded thumbnail in memory either way. This only turns off the disk cache for a
 * remote file. The memory cache stays enabled.
 */
class RemoteImageDiskCacheInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val path = chain.request.data.asPath()
        if (path != null && path.isRemotePath && !Settings.CACHE_REMOTE_FILES_ON_DISK.valueCompat) {
            val request = chain.request.newBuilder()
                .diskCachePolicy(CachePolicy.DISABLED)
                .build()
            return chain.proceed(request)
        }
        return chain.proceed(chain.request)
    }

    private fun Any.asPath(): Path? =
        when (this) {
            is Path -> this
            is Pair<*, *> -> first as? Path
            else -> null
        }
}
