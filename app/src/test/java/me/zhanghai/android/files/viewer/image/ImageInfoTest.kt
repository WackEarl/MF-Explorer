/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import java8.nio.file.attribute.BasicFileAttributes
import me.zhanghai.android.files.file.asMimeType
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

/** Issue #54: bounds and EXIF orientation come from one open and the header bytes only. */
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
        val (counting, source) = jpeg.countingSource()

        val info = source.use { readImageInfo(it, attributes, "image/jpeg".asMimeType()) }

        assertEquals(4400, info.width)
        assertEquals(2000, info.height)
        assertTrue(info.shouldUseLargeImageView)
        assertEquals(SubsamplingScaleImageView.ORIENTATION_90, info.largeImageOrientation.rotation)
        assertFalse(info.largeImageOrientation.isMirrored)
        assertTrue("read ${counting.bytesRead} bytes", counting.bytesRead <= 64 * 1024)
    }

    @Test
    fun normalImageSkipsTheOrientation() {
        val main = TestJpegs.encode(TestJpegs.quadrants(400, 300))
        val jpeg = TestJpegs.withExif(main, orientation = 6, thumbnail = null)
        val (_, source) = jpeg.countingSource()

        val info = source.use { readImageInfo(it, attributes, "image/jpeg".asMimeType()) }

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
