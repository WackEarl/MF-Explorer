/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.coil

import android.content.Context
import android.graphics.Bitmap
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
import me.zhanghai.android.files.testing.TestJpegs
import me.zhanghai.android.files.testing.TestRemoteFiles
import me.zhanghai.android.files.util.getDimensionPixelSize
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The thumbnail request FileListAdapter makes for a remote camera JPEG, through the app's own
 * image loader and components, counting the bytes read from the file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h915dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListThumbnailRequestTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val files = TestRemoteFiles()
    private lateinit var cacheDirectory: File
    private lateinit var imageLoader: ImageLoader

    /** The list item: a square of large_icon_size, center-cropped. */
    private val listSize: Int
        get() = context.getDimensionPixelSize(R.dimen.large_icon_size)

    /** A grid cell of two columns on this 411dp screen, 1.78:1 and center-cropped. */
    private val gridSize: Pair<Int, Int>
        get() {
            val margin = context.getDimensionPixelSize(R.dimen.screen_edge_margin)
            val width = context.resources.displayMetrics.widthPixels / 2 - 2 * margin
            return width to (width / 1.78f).roundToInt()
        }

    @Before
    fun setUp() {
        cacheDirectory = Files.createTempDirectory("image-cache").toFile()
        imageLoader = newLoader()
    }

    @After
    fun tearDown() {
        imageLoader.shutdown()
        cacheDirectory.deleteRecursively()
    }

    private fun newLoader(): ImageLoader =
        // Both settings at their defaults.
        newImageLoader(
            context, readRemoteFilesForThumbnail = { true }, cacheRemoteFilesOnDisk = { true }
        ) {
            diskCache { DiskCache.Builder().directory(cacheDirectory).build() }
        }

    /** As FileListAdapter loads a thumbnail into its center-cropped image view. */
    private fun load(
        data: Pair<Path, BasicFileAttributes>,
        width: Int,
        height: Int,
        loader: ImageLoader = imageLoader
    ): SuccessResult {
        val request = ImageRequest.Builder(context)
            .data(data)
            .size(width, height)
            .scale(Scale.FILL)
            .build()
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

    /**
     * A 3:2 camera photo of several MiB: a 160x120 EXIF thumbnail with black bars, then about
     * 100 KiB of maker notes and XMP before the frame header, like a full-frame camera writes.
     */
    private fun cameraJpeg(): ByteArray {
        val main = TestJpegs.encode(TestJpegs.quadrants(3000, 2000, noise = true))
        val thumbnail = TestJpegs.encode(TestJpegs.letterboxed(160, 120, 160, 106), 90)
        return TestJpegs.withExif(main, 1, thumbnail, paddingBytes = 100 * 1024)
    }

    private val headerLimit = ExifThumbnail.MAX_HEADER_BYTES + 64 * 1024L

    @Test
    fun listThumbnailOfACameraJpegReadsOnlyTheHeader() {
        val jpeg = cameraJpeg()
        assertTrue("test image too small: ${jpeg.size}", jpeg.size > 2 * 1024 * 1024)
        val data = files.add("DSC00001.JPG", jpeg)

        val result = load(data, listSize, listSize)

        assertTrue("read ${files.bytesRead} of ${jpeg.size} bytes",
            files.bytesRead.get() <= headerLimit)
        assertEquals(1, files.opens.get())
        // The black bars are cut off: what is left is 3:2.
        val bitmap = (result.drawable as BitmapDrawable).bitmap
        assertAspect(1.5, bitmap.width, bitmap.height)
    }

    @Test
    fun gridThumbnailOfACameraJpegReadsOnlyTheHeader() {
        val jpeg = cameraJpeg()
        val data = files.add("DSC00002.JPG", jpeg)
        val (width, height) = gridSize
        assertTrue("grid cell $width x $height is not a thumbnail size",
            RemoteThumbnailDiskCache.thumbnailSize(coil.size.Size(width, height)) != null)

        load(data, width, height)

        assertTrue("read ${files.bytesRead} of ${jpeg.size} bytes",
            files.bytesRead.get() <= headerLimit)
    }

    @Test
    fun aFullSizeThumbnailOfTheSameAspectRatioIsUsed() {
        val main = TestJpegs.encode(TestJpegs.quadrants(2400, 1800, noise = true))
        val thumbnail = TestJpegs.encode(TestJpegs.quadrants(512, 384), 90)
        val jpeg = TestJpegs.withExif(main, 6, thumbnail)
        val data = files.add("IMG_0003.jpg", jpeg)
        val (width, height) = gridSize

        val result = load(data, width, height)

        assertTrue("read ${files.bytesRead} of ${jpeg.size} bytes",
            files.bytesRead.get() <= headerLimit)
        // Portrait after the EXIF rotation.
        val bitmap = (result.drawable as BitmapDrawable).bitmap
        assertAspect(0.75, bitmap.width, bitmap.height)
    }

    @Test
    fun aThumbnailOfAnotherPictureFallsBackToTheFullImage() {
        // 4:3 content without bars cannot be a thumbnail of this 3:2 photo.
        val main = TestJpegs.encode(TestJpegs.quadrants(1500, 1000, noise = true))
        val thumbnail = TestJpegs.encode(TestJpegs.quadrants(160, 120), 90)
        val jpeg = TestJpegs.withExif(main, 1, thumbnail)
        val data = files.add("DSC00004.JPG", jpeg)

        val result = load(data, listSize, listSize)

        assertEquals(jpeg.size.toLong(), files.bytesRead.get())
        val bitmap = (result.drawable as BitmapDrawable).bitmap
        assertAspect(1.5, bitmap.width, bitmap.height)
    }

    // From API 28 the stored WebP goes to ImageDecoder, which Robolectric cannot run.
    @Config(sdk = [27])
    @Test
    fun aFolderOpenedAgainReadsNothingFromTheFile() {
        val jpeg = cameraJpeg()
        val data = files.add("DSC00005.JPG", jpeg)
        load(data, listSize, listSize)
        // The disk cache is written off the request.
        waitForDiskCacheEntry()
        files.reset()

        // A new loader has an empty memory cache, as after the app was restarted.
        val loader = newLoader()
        try {
            load(data, listSize, listSize, loader)
        } finally {
            loader.shutdown()
        }

        assertEquals(0, files.opens.get())
        assertEquals(0L, files.bytesRead.get())
    }

    private fun waitForDiskCacheEntry() {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (imageLoader.diskCache!!.size > 0) {
                return
            }
            Thread.sleep(20)
        }
        throw AssertionError("disk cache was not written")
    }

    /**
     * Real camera JPEGs in the directory named by MFX_CAMERA_SAMPLES, too big for the
     * repository. Writes the bytes each thumbnail read to MFX_CAMERA_SAMPLES_REPORT and the
     * thumbnails to the directory MFX_CAMERA_SAMPLES_OUT, if set.
     */
    @Test
    fun realCameraSamples() {
        val directory = System.getenv("MFX_CAMERA_SAMPLES")?.let { File(it) }
        assumeTrue(directory != null && directory.isDirectory)
        val report = StringBuilder()
        val (gridWidth, gridHeight) = gridSize
        val samples = directory!!.listFiles { file ->
            file.name.endsWith(".jpg", ignoreCase = true)
        }!!.sortedBy { it.name }
        for (sample in samples) {
            val bytes = sample.readBytes()
            for ((label, size) in listOf(
                "list" to (listSize to listSize), "grid" to (gridWidth to gridHeight)
            )) {
                files.reset()
                val loader = newLoader()
                cacheDirectory.listFiles()?.forEach { it.deleteRecursively() }
                val data = files.add("$label-${sample.name}", bytes)
                val result = try {
                    load(data, size.first, size.second, loader)
                } finally {
                    loader.shutdown()
                }
                val bitmap = (result.drawable as BitmapDrawable).bitmap
                System.getenv("MFX_CAMERA_SAMPLES_OUT")?.let { out ->
                    File(out, "$label-${sample.nameWithoutExtension}.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
                report.append(
                    "${sample.name}\t$label ${size.first}x${size.second}\t" +
                        "read ${files.bytesRead.get()} of ${bytes.size} bytes " +
                        "(${files.bytesRead.get() * 100 / bytes.size}%)\t" +
                        "bitmap ${bitmap.width}x${bitmap.height}\n"
                )
            }
        }
        System.getenv("MFX_CAMERA_SAMPLES_REPORT")?.let { File(it).writeText(report.toString()) }
        println(report)
    }

    private fun assertAspect(expected: Double, width: Int, height: Int) {
        val actual = width.toDouble() / height
        assertTrue("aspect ratio of $width x $height is not $expected",
            abs(actual - expected) / expected < 0.03)
    }
}
