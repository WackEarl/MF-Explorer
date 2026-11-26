/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import coil.ImageLoader
import coil.disk.DiskCache
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Scale
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.runBlocking
import me.zhanghai.android.files.R
import me.zhanghai.android.files.coil.newImageLoader
import me.zhanghai.android.files.testing.TestJpegs
import me.zhanghai.android.files.testing.TestRemoteFiles
import me.zhanghai.android.files.util.getDimensionPixelSize
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * The viewer's full image request after the file list showed the thumbnail of the same file,
 * through the app's own image loader and components.
 */
@RunWith(RobolectricTestRunner::class)
// From API 28 the stored WebP thumbnail goes to ImageDecoder, which Robolectric cannot run.
@Config(sdk = [27], qualifiers = "w411dp-h915dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FullImageRequestTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val files = TestRemoteFiles()
    private lateinit var cacheDirectory: File
    private val loaders = mutableListOf<ImageLoader>()

    @Before
    fun setUp() {
        cacheDirectory = Files.createTempDirectory("image-cache").toFile()
    }

    @After
    fun tearDown() {
        loaders.forEach { it.shutdown() }
        cacheDirectory.deleteRecursively()
    }

    private fun newLoader(): ImageLoader =
        newImageLoader(
            context, readRemoteFilesForThumbnail = { true }, cacheRemoteFilesOnDisk = { true }
        ) {
            diskCache { DiskCache.Builder().directory(cacheDirectory).build() }
        }.also { loaders += it }

    private fun execute(loader: ImageLoader, request: ImageRequest): SuccessResult {
        // Coil hops onto the main thread, so it has to keep running while the test waits.
        val result = loader.enqueue(request).job.let { job ->
            while (!job.isCompleted) {
                shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
                Thread.sleep(5)
            }
            runBlocking { job.await() }
        }
        if (result is ErrorResult) {
            throw AssertionError("request failed", result.throwable)
        }
        return result as SuccessResult
    }

    /** As FileListAdapter loads a thumbnail into its center-cropped image view. */
    private fun listThumbnail(loader: ImageLoader, data: Pair<Path, BasicFileAttributes>) {
        val size = context.getDimensionPixelSize(R.dimen.large_icon_size)
        execute(
            loader,
            ImageRequest.Builder(context).data(data).size(size, size).scale(Scale.FILL).build()
        )
    }

    private fun waitForDiskCacheEntry(loader: ImageLoader) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (loader.diskCache!!.size > 0) {
                return
            }
            Thread.sleep(20)
        }
        throw AssertionError("disk cache was not written")
    }

    /**
     * B1: after a restart the list shows the stored thumbnail, which is not marked as sampled.
     * The viewer must not take it from the memory cache as the full image.
     */
    @Test
    fun theFullImageIsLoadedAfterTheListShowedAStoredThumbnail() {
        // A noise photo without an EXIF thumbnail, like the test share's small images.
        val jpeg = TestJpegs.encode(TestJpegs.quadrants(1200, 800, noise = true))
        val (path, attributes) = files.add("img-03.jpg", jpeg)
        val firstRun = newLoader()
        listThumbnail(firstRun, path to attributes)
        waitForDiskCacheEntry(firstRun)

        // A new loader has an empty memory cache, as after the app was restarted.
        val loader = newLoader()
        listThumbnail(loader, path to attributes)
        val result = execute(
            loader, ImageRequest.Builder(context).fullImage(path, attributes).build()
        )

        val bitmap = (result.drawable as BitmapDrawable).bitmap
        assertEquals(1200 to 800, bitmap.width to bitmap.height)
        // The thumbnail stays where the placeholder looks for it.
        val placeholderKey = ThumbnailPlaceholder.memoryCacheKey(path, attributes)
        val placeholder = loader.memoryCache!![placeholderKey]!!.bitmap
        assertTrue("placeholder is ${placeholder.width} wide", placeholder.width < 1200)
    }
}
