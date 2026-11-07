/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.coil

import android.os.AsyncTask
import android.os.Build
import coil.Coil
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat

fun initializeCoil() {
    Coil.setImageLoader(
        ImageLoader.Builder(application)
            .components {
                add(RemoteImageDiskCacheInterceptor())
                add(AppIconApplicationInfoKeyer())
                add(AppIconApplicationInfoFetcherFactory(application))
                add(AppIconPackageNameKeyer())
                add(AppIconPackageNameFetcherFactory(application))
                add(PathAttributesKeyer())
                add(PathAttributesFetcher.Factory(application))
                add(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        ImageDecoderDecoder.Factory()
                    } else {
                        GifDecoder.Factory()
                    }
                )
                add(SvgDecoder.Factory(false))
            }
            .build()
    )
}

/** Settings touch the file-system provider, so this must run after it is installed. */
fun observeRemoteImageDiskCache() {
    var caching = Settings.CACHE_REMOTE_FILES_ON_DISK.valueCompat
    Settings.CACHE_REMOTE_FILES_ON_DISK.observeForever { enabled ->
        if (caching && !enabled) {
            AsyncTask.THREAD_POOL_EXECUTOR.execute {
                Coil.imageLoader(application).diskCache?.clear()
            }
        }
        caching = enabled
    }
}
