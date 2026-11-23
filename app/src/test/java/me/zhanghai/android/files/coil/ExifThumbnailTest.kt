/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.coil

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import coil.decode.BitmapFactoryDecoder
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.SourceResult
import coil.decode.ImageSource
import coil.request.Options
import coil.size.Scale
import coil.size.Size
import kotlinx.coroutines.runBlocking
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.testing.TestJpegs
import me.zhanghai.android.files.testing.countingSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.random.Random

/** Issue #49: a JPEG thumbnail comes from the EXIF thumbnail and the first few KiB of the file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExifThumbnailTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private fun jpeg(
        width: Int,
        height: Int,
        orientation: Int?,
        thumbnailWidth: Int?,
        thumbnailHeight: Int?,
        noise: Boolean = false
    ): ByteArray {
        val main = TestJpegs.encode(TestJpegs.quadrants(width, height, noise))
        val thumbnail = if (thumbnailWidth != null && thumbnailHeight != null) {
            TestJpegs.encode(TestJpegs.quadrants(thumbnailWidth, thumbnailHeight), 80)
        } else {
            null
        }
        return TestJpegs.withExif(main, orientation, thumbnail)
    }

    @Test
    fun readsOnlyTheHeaderOfALargePhoto() {
        val jpeg = jpeg(3000, 2000, 1, 480, 320, noise = true)
        assertTrue("test image too small: ${jpeg.size}", jpeg.size > 2 * 1024 * 1024)
        val (counting, source) = jpeg.countingSource()

        val bitmap = source.use { ExifThumbnail.decode(it, 240, 160, Scale.FIT) }

        assertNotNull(bitmap)
        // Sampled like Coil: the largest power of two that keeps at least the requested size.
        assertEquals(240, bitmap!!.width)
        assertEquals(160, bitmap.height)
        assertTrue("read ${counting.bytesRead} of ${jpeg.size} bytes",
            counting.bytesRead <= 64 * 1024)
    }

    @Test
    fun keepsASmallerThumbnailUnsampled() {
        val jpeg = jpeg(1200, 800, 1, 480, 320)
        val bitmap = jpeg.countingSource().second.use {
            ExifThumbnail.decode(it, 600, 400, Scale.FIT)
        }

        assertEquals(480, bitmap!!.width)
        assertEquals(320, bitmap.height)
    }

    @Test
    fun appliesEveryExifOrientation() {
        val w = 600
        val h = 400
        for (orientation in 1..8) {
            val jpeg = jpeg(w, h, orientation, 300, 200)
            val rotated = orientation in 5..8
            val requestWidth = if (rotated) 200 else 300
            val requestHeight = if (rotated) 300 else 200

            val bitmap = jpeg.countingSource().second.use {
                ExifThumbnail.decode(it, requestWidth, requestHeight, Scale.FIT)
            }

            assertNotNull("orientation $orientation", bitmap)
            assertEquals("orientation $orientation", requestWidth, bitmap!!.width)
            assertEquals("orientation $orientation", requestHeight, bitmap.height)
            assertQuadrants(bitmap, orientation, 300, 200)
        }
    }

    /** Where stored pixel (x, y) is displayed, as defined by the EXIF orientation tag. */
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

    private fun assertQuadrants(bitmap: Bitmap, orientation: Int, w: Int, h: Int) {
        val quadrants = listOf(
            Triple(w / 4, h / 4, TestJpegs.TOP_LEFT),
            Triple(3 * w / 4, h / 4, TestJpegs.TOP_RIGHT),
            Triple(w / 4, 3 * h / 4, TestJpegs.BOTTOM_LEFT),
            Triple(3 * w / 4, 3 * h / 4, TestJpegs.BOTTOM_RIGHT)
        )
        for ((x, y, color) in quadrants) {
            val (dx, dy) = exifDisplay(orientation, x, y, w, h)
            val actual = bitmap.getPixel(dx, dy)
            assertTrue(
                "orientation $orientation at ($dx, $dy): expected ${Integer.toHexString(color)}" +
                    " but was ${Integer.toHexString(actual)}",
                isClose(color, actual)
            )
        }
    }

    private fun isClose(expected: Int, actual: Int): Boolean =
        abs(Color.red(expected) - Color.red(actual)) < 48 &&
            abs(Color.green(expected) - Color.green(actual)) < 48 &&
            abs(Color.blue(expected) - Color.blue(actual)) < 48

    @Test
    fun missingThumbnailFallsBackAndKeepsTheWholeFile() {
        val jpeg = jpeg(1200, 800, 6, null, null)
        val (_, source) = jpeg.countingSource()

        source.use {
            assertNull(ExifThumbnail.decode(it, 240, 160, Scale.FIT))
            // Nothing was consumed: the full decode still gets every byte.
            assertEquals(jpeg.size.toLong(), it.readByteArray().size.toLong())
        }
    }

    @Test
    fun tooSmallThumbnailFallsBackToTheFullDecodeWithOrientation() {
        // 160x107 for a 512x384 request needs more than twice the scaling up.
        val jpeg = jpeg(1200, 800, 6, 160, 107)
        val (_, source) = jpeg.countingSource()

        assertNull(ExifThumbnail.decode(source, 512, 384, Scale.FIT))
        // What PathAttributesFetcher hands to Coil next, on the same source.
        val result = runBlocking {
            BitmapFactoryDecoder(
                ImageSource(source, context), Options(context, size = Size(512, 384))
            ).decode()
        }
        val bitmap = (result.drawable as BitmapDrawable).bitmap
        // Rotated by 90 degrees and scaled to fit.
        assertEquals(256, bitmap.width)
        assertEquals(384, bitmap.height)
    }

    @Test
    fun paddedThumbnailOfAnotherAspectRatioFallsBack() {
        // A 4:3 thumbnail of a 3:2 photo has black bars.
        val jpeg = jpeg(1200, 800, 1, 512, 384)
        val bitmap = jpeg.countingSource().second.use {
            ExifThumbnail.decode(it, 256, 192, Scale.FIT)
        }

        assertNull(bitmap)
    }

    @Test
    fun aFileWithoutExifStopsReadingAtTheLimit() {
        // Starts like a JPEG but never reaches an image segment.
        val random = Random(2)
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(4 * 1024 * 1024) {
            random.nextInt().toByte()
        }
        val (counting, source) = bytes.countingSource()

        source.use { assertNull(ExifThumbnail.decode(it, 240, 160, Scale.FIT)) }

        assertTrue("read ${counting.bytesRead} bytes",
            counting.bytesRead <= ExifThumbnail.MAX_HEADER_BYTES + 64 * 1024)
    }

    @Test
    fun frameHeaderBeyondTheLimitUsesTheExifPixelDimensions() {
        val main = TestJpegs.encode(TestJpegs.quadrants(1200, 800))
        val thumbnail = TestJpegs.encode(TestJpegs.quadrants(300, 200), 80)
        val jpeg = TestJpegs.withExif(
            main, 6, thumbnail, pixelDimensions = 1200 to 800,
            paddingBytes = ExifThumbnail.MAX_HEADER_BYTES + 64 * 1024
        )
        val (counting, source) = jpeg.countingSource()

        val bitmap = source.use { ExifThumbnail.decode(it, 200, 300, Scale.FIT) }

        assertNotNull(bitmap)
        assertEquals(200, bitmap!!.width)
        assertEquals(300, bitmap.height)
        assertTrue("read ${counting.bytesRead} bytes",
            counting.bytesRead <= ExifThumbnail.MAX_HEADER_BYTES + 64 * 1024)
    }

    @Test
    fun withoutAnyImageSizeTheThumbnailIsNotUsed() {
        val main = TestJpegs.encode(TestJpegs.quadrants(1200, 800))
        val thumbnail = TestJpegs.encode(TestJpegs.quadrants(300, 200), 80)
        val jpeg = TestJpegs.withExif(
            main, 1, thumbnail, paddingBytes = ExifThumbnail.MAX_HEADER_BYTES + 64 * 1024
        )

        assertNull(jpeg.countingSource().second.use {
            ExifThumbnail.decode(it, 300, 200, Scale.FIT)
        })
    }

    @Test
    fun anExifThumbnailClosesTheSource() {
        val jpeg = jpeg(1200, 800, 1, 300, 200)
        val (counting, source) = jpeg.countingSource()

        val result = imageResult(
            source, MimeType.IMAGE_JPEG, 300 to 200, Options(context), DataSource.NETWORK
        )

        assertTrue(result is DrawableResult)
        assertTrue(counting.isClosed)
    }

    @Test
    fun aFullDecodeGetsTheOpenSource() {
        val jpeg = jpeg(1200, 800, 1, null, null)
        val (counting, source) = jpeg.countingSource()

        val result = imageResult(
            source, MimeType.IMAGE_JPEG, 300 to 200, Options(context), DataSource.NETWORK
        )

        assertTrue(result is SourceResult)
        assertFalse(counting.isClosed)
        assertEquals(jpeg.size.toLong(), (result as SourceResult).source.source().readByteArray()
            .size.toLong())
        result.source.close()
        assertTrue(counting.isClosed)
    }

    @Test
    fun usableThresholds() {
        // Exactly half the requested size is still fine, a little less is not.
        assertTrue(ExifThumbnail.isUsable(256, 192, 4000, 3000, 512, 384, Scale.FIT))
        assertFalse(ExifThumbnail.isUsable(250, 188, 4000, 3000, 512, 384, Scale.FIT))
        assertFalse(ExifThumbnail.isUsable(512, 384, 0, 0, 256, 192, Scale.FIT))
        assertFalse(ExifThumbnail.isUsable(0, 0, 4000, 3000, 256, 192, Scale.FIT))
    }
}
