/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.drawable.toDrawable
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.Fetcher
import coil.request.ImageRequest
import coil.request.SuccessResult
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.zhanghai.android.files.coil.PathAttributesKeyer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit

/** Issue #50: the viewer finds the file list's thumbnail and shows it the right way round. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThumbnailPlaceholderTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private val path = Proxy.newProxyInstance(
        Path::class.java.classLoader, arrayOf(Path::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "toString" -> "nfs://192.0.2.1/export/a.jpg"
            "hashCode" -> 1
            "equals" -> proxy === args?.get(0)
            else -> throw AssertionError(method.name)
        }
    } as Path

    private val attributes = Proxy.newProxyInstance(
        BasicFileAttributes::class.java.classLoader, arrayOf(BasicFileAttributes::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "lastModifiedTime" -> FileTime.from(1_700_000_000_000, TimeUnit.MILLISECONDS)
            "hashCode" -> 2
            "equals" -> proxy === args?.get(0)
            "toString" -> "attributes"
            else -> throw UnsupportedOperationException(method.name)
        }
    } as BasicFileAttributes

    @Test
    fun findsTheThumbnailTheFileListLoaded() = runBlocking {
        val thumbnail = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        val imageLoader = ImageLoader.Builder(context)
            .dispatcher(Dispatchers.Unconfined)
            .interceptorDispatcher(Dispatchers.Unconfined)
            .components {
                add(PathAttributesKeyer())
                add(Fetcher.Factory<Pair<Path, BasicFileAttributes>> { _, options, _ ->
                    Fetcher {
                        DrawableResult(
                            thumbnail.toDrawable(options.context.resources), true,
                            DataSource.NETWORK
                        )
                    }
                })
            }
            .build()
        // Like FileListAdapter: no transformations, no parameters, a view-sized request.
        val result = imageLoader.execute(
            ImageRequest.Builder(context).data(path to attributes).size(128, 96).build()
        )
        assertTrue(result.toString(), result is SuccessResult)

        val cached = imageLoader.memoryCache!![
            ThumbnailPlaceholder.memoryCacheKey(path, attributes)
        ]

        assertNotNull(cached)
        assertSame(thumbnail, cached!!.bitmap)
    }

    @Test
    fun normalOrientationKeepsTheCachedBitmap() {
        val bitmap = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888)
        assertSame(
            bitmap, ThumbnailPlaceholder.toStoredOrientation(bitmap, LargeImageOrientation.NORMAL)
        )
    }

    /** Coil shows stored pixel (x, y) here, as defined by the EXIF orientation tag. */
    private fun exifDisplay(orientation: Int, x: Int, y: Int, w: Int, h: Int): Pair<Int, Int> =
        when (orientation) {
            1 -> x to y
            2 -> (w - 1 - x) to y
            3 -> (w - 1 - x) to (h - 1 - y)
            4 -> x to (h - 1 - y)
            5 -> y to x
            6 -> (h - 1 - y) to x
            7 -> (h - 1 - y) to (w - 1 - x)
            8 -> y to (w - 1 - x)
            else -> error(orientation)
        }

    @Test
    fun everyOrientationGoesBackToTheStoredPixels() {
        val w = 4
        val h = 2
        val stored = IntArray(w * h) { Color.rgb(it * 30, 255 - it * 30, it * 10) }
        for (orientation in 1..8) {
            val rotated = orientation in 5..8
            val dw = if (rotated) h else w
            val dh = if (rotated) w else h
            val displayedPixels = IntArray(dw * dh)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val (dx, dy) = exifDisplay(orientation, x, y, w, h)
                    displayedPixels[dy * dw + dx] = stored[y * w + x]
                }
            }
            val displayed = Bitmap.createBitmap(displayedPixels, dw, dh, Bitmap.Config.ARGB_8888)

            val result = ThumbnailPlaceholder.toStoredOrientation(
                displayed, LargeImageOrientation.fromExifOrientation(orientation)
            )

            assertEquals("orientation $orientation width", w, result.width)
            assertEquals("orientation $orientation height", h, result.height)
            val actual = IntArray(w * h)
            result.getPixels(actual, 0, w, 0, 0, w, h)
            assertEquals("orientation $orientation", stored.toList(), actual.toList())
        }
    }
}
