/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.file.asMimeType
import me.zhanghai.android.files.testing.CountingSource
import me.zhanghai.android.files.testing.TestJpegs
import me.zhanghai.android.files.testing.countingSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.reflect.Proxy

/**
 * Issue #54: a JPEG's bounds and EXIF orientation come from one open and the header bytes only.
 * Other formats must not be peeked at into memory.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageInfoTest {
    private val attributes = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(BasicFileAttributes::class.java)
    ) { _, _, _ -> throw UnsupportedOperationException() } as BasicFileAttributes

    @Test
    fun largeImageReadsBoundsAndOrientationFromTheHeaderOnly() {
        // Wider than 2:1 and over 2048 px, so it goes to the large image view.
        val main = TestJpegs.encode(TestJpegs.quadrants(4400, 2000, noise = true))
        val jpeg = TestJpegs.withExif(main, orientation = 6, thumbnail = null)
        assertTrue("test image too small: ${jpeg.size}", jpeg.size > 2 * 1024 * 1024)
        val opened = mutableListOf<CountingSource>()

        val info = readImageInfo(attributes, "image/jpeg".asMimeType()) {
            jpeg.countingSource().also { opened += it.first }.second
        }

        assertEquals(4400, info.width)
        assertEquals(2000, info.height)
        assertTrue(info.shouldUseLargeImageView)
        assertEquals(SubsamplingScaleImageView.ORIENTATION_90, info.largeImageOrientation.rotation)
        assertFalse(info.largeImageOrientation.isMirrored)
        assertEquals(1, opened.size)
        assertTrue("read ${opened[0].bytesRead} bytes", opened[0].bytesRead <= 64 * 1024)
        assertTrue(opened[0].isClosed)
    }

    @Test
    fun webpWithExifAtTheEndIsNotPeekedIntoMemory() {
        val webp = TestJpegs.webpWithExifAtEnd(
            TestJpegs.quadrants(4400, 2000, noise = true), orientation = 8
        )
        assertTrue("test image too small: ${webp.size}", webp.size > 2 * 1024 * 1024)
        val opened = mutableListOf<CountingSource>()

        val info = readImageInfo(attributes, "image/webp".asMimeType()) {
            webp.countingSource().also { opened += it.first }.second
        }

        assertEquals("image/webp", info.mimeType.value)
        assertEquals(4400, info.width)
        assertTrue(info.shouldUseLargeImageView)
        assertEquals(SubsamplingScaleImageView.ORIENTATION_270, info.largeImageOrientation.rotation)
        // One open per read and no peeking: both reads stream through their own source, so
        // the app never holds the file in a peek buffer. The platform WebP decoder itself
        // reads the whole file even for its bounds, as it did before.
        assertEquals(2, opened.size)
        assertTrue(opened.all { it.isClosed })
        for (source in opened) {
            assertTrue("buffered ${source.maxBuffered} bytes", source.maxBuffered <= 256 * 1024)
        }
    }

    @Test
    fun normalImageSkipsTheOrientation() {
        val main = TestJpegs.encode(TestJpegs.quadrants(400, 300))
        val jpeg = TestJpegs.withExif(main, orientation = 6, thumbnail = null)
        val info = readImageInfo(attributes, "image/jpeg".asMimeType()) {
            jpeg.countingSource().second
        }

        assertFalse(info.shouldUseLargeImageView)
        assertEquals(SubsamplingScaleImageView.ORIENTATION_0, info.largeImageOrientation.rotation)
    }

    @Test
    fun hugeImageDoesNotOverflowTheBitmapSize() {
        val info = ImageInfo(
            attributes, 40000, 30000, "image/jpeg".asMimeType(), LargeImageOrientation.NORMAL
        )
        assertTrue(info.shouldUseLargeImageView)
    }
}
