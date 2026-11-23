/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.coil

import android.content.Context
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
    Coil.setImageLoader(newImageLoader(application))
}

/**
 * The app's image loader, also built by tests to drive the same components. The settings are
 * read on every request.
 */
internal fun newImageLoader(
    context: Context,
    readRemoteFilesForThumbnail: () -> Boolean = {
        Settings.READ_REMOTE_FILES_FOR_THUMBNAIL.valueCompat
    },
    cacheRemoteFilesOnDisk: () -> Boolean = { Settings.CACHE_REMOTE_FILES_ON_DISK.valueCompat },
    configure: ImageLoader.Builder.() -> Unit = {}
): ImageLoader {
    lateinit var imageLoader: ImageLoader
    imageLoader = ImageLoader.Builder(context)
        .components {
            add(RemoteImageDiskCacheInterceptor({ imageLoader.diskCache }, cacheRemoteFilesOnDisk))
            add(AppIconApplicationInfoKeyer())
            add(AppIconApplicationInfoFetcherFactory(context))
            add(AppIconPackageNameKeyer())
            add(AppIconPackageNameFetcherFactory(context))
            add(PathAttributesKeyer())
            add(PathAttributesFetcher.Factory(context, readRemoteFilesForThumbnail))
            add(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ImageDecoderDecoder.Factory()
                } else {
                    GifDecoder.Factory()
                }
            )
            add(SvgDecoder.Factory(false))
        }
        .apply(configure)
        .build()
    return imageLoader
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
