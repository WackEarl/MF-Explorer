/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.coil

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
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
    fun aSmallThumbnailIsUsedForALargerRequest() {
        // 160x107 for a 512x384 request is scaled up when displayed, not read from the photo.
        val jpeg = jpeg(1200, 800, 6, 160, 107)

        val bitmap = jpeg.countingSource().second.use {
            ExifThumbnail.decode(it, 384, 512, Scale.FIT)
        }

        assertNotNull(bitmap)
        // Rotated by 90 degrees.
        assertEquals(107, bitmap!!.width)
        assertEquals(160, bitmap.height)
    }

    @Test
    fun theBlackBarsOfAPaddedThumbnailAreCutOff() {
        // A 3:2 camera photo shown portrait, with the 160x120 thumbnail every camera writes.
        for (orientation in listOf(1, 6, 8)) {
            val main = TestJpegs.encode(TestJpegs.quadrants(1500, 1000))
            val thumbnail = TestJpegs.encode(TestJpegs.letterboxed(160, 120, 160, 106), 90)
            val jpeg = TestJpegs.withExif(main, orientation, thumbnail)

            val bitmap = jpeg.countingSource().second.use {
                ExifThumbnail.decode(it, 105, 105, Scale.FILL)
            }

            assertNotNull("orientation $orientation", bitmap)
            val rotated = orientation != 1
            // Exactly the 160x106 the camera drew, without the black rows around it.
            assertEquals("orientation $orientation", if (rotated) 106 else 160, bitmap!!.width)
            assertEquals("orientation $orientation", if (rotated) 160 else 106, bitmap.height)
            assertQuadrants(bitmap, orientation, 160, 106)
            for ((x, y) in listOf(
                0 to 0, bitmap.width - 1 to 0, 0 to bitmap.height - 1,
                bitmap.width - 1 to bitmap.height - 1
            )) {
                val pixel = bitmap.getPixel(x, y)
                assertTrue("orientation $orientation: black at ($x, $y)",
                    Color.red(pixel) + Color.green(pixel) + Color.blue(pixel) > 3 * 60)
            }
        }
    }

    @Test
    fun aPortraitThumbnailOfALandscapeBufferIsPillarboxed() {
        val main = TestJpegs.encode(TestJpegs.quadrants(1000, 1500))
        val thumbnail = TestJpegs.encode(TestJpegs.letterboxed(160, 120, 80, 120), 90)
        val jpeg = TestJpegs.withExif(main, 1, thumbnail)

        val bitmap = jpeg.countingSource().second.use {
            ExifThumbnail.decode(it, 105, 105, Scale.FILL)
        }

        assertEquals(80, bitmap!!.width)
        assertEquals(120, bitmap.height)
    }

    @Test
    fun aThumbnailOfAnotherAspectRatioWithoutBarsFallsBack() {
        // 4:3 content edge to edge cannot be the whole of a 3:2 photo.
        val jpeg = jpeg(1200, 800, 1, 512, 384)
        val (_, source) = jpeg.countingSource()

        source.use {
            assertNull(ExifThumbnail.decode(it, 256, 192, Scale.FIT))
            assertEquals(jpeg.size.toLong(), it.readByteArray().size.toLong())
        }
    }

    @Test
    fun aMismatchedThumbnailFallsBackToTheFullDecodeWithOrientation() {
        val jpeg = jpeg(1200, 800, 6, 160, 160)
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
    fun contentRects() {
        // Same aspect ratio, within the tolerance.
        assertEquals(Rect(0, 0, 160, 107), ExifThumbnail.contentRect(160, 107, 6000, 4000))
        assertEquals(Rect(0, 0, 160, 120), ExifThumbnail.contentRect(160, 120, 4608, 3456))
        // Letterboxed 3:2 in 4:3, as Sony, Canon, Nikon and Pentax write it.
        assertEquals(Rect(0, 6, 160, 113), ExifThumbnail.contentRect(160, 120, 9504, 6336))
        // Pillarboxed portrait in landscape.
        assertEquals(Rect(40, 0, 120, 120), ExifThumbnail.contentRect(160, 120, 1000, 1500))
        assertNull(ExifThumbnail.contentRect(160, 120, 0, 0))
        assertNull(ExifThumbnail.contentRect(0, 0, 4000, 3000))
    }
}
