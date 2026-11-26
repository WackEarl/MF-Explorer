/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

/** B7: a decoder of an image that its view no longer shows never reaches the view. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CurrentBindDecoderTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val uri: Uri = Uri.parse("mfx-large-image:")

    private class FakeDecoder(private val initError: Exception? = null) : ImageRegionDecoder {
        var recycled = false
        var tiles = 0

        override fun init(context: Context, uri: Uri): Point {
            initError?.let { throw it }
            return Point(9504, 6336)
        }

        override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
            tiles++
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }

        override fun isReady(): Boolean = !recycled

        override fun recycle() {
            recycled = true
        }
    }

    @Test
    fun aDecoderOfTheCurrentBindIsHandedOver() {
        val fake = FakeDecoder()
        val decoder = CurrentBindDecoder(fake) { true }
        assertEquals(Point(9504, 6336), decoder.init(context, uri))
        decoder.decodeRegion(Rect(0, 0, 10, 10), 1)
        assertEquals(1, fake.tiles)
        assertFalse(fake.recycled)
    }

    @Test
    fun aDecoderThatBecameStaleWhileReadingIsReleasedNotHandedOver() {
        val fake = FakeDecoder()
        val decoder = CurrentBindDecoder(fake) { false }
        try {
            decoder.init(context, uri)
            fail()
        } catch (e: StaleDecoderException) {
            assertTrue(fake.recycled)
        }
    }

    @Test
    fun aFailureOfAStaleDecoderIsNotAnErrorOfTheShownImage() {
        val error = IOException("The NFS server is unreachable")
        val decoder = CurrentBindDecoder(FakeDecoder(error)) { false }
        try {
            decoder.init(context, uri)
            fail()
        } catch (e: StaleDecoderException) {
            assertSame(error, e.cause)
        }
    }

    @Test
    fun aFailureOfTheCurrentDecoderIsKept() {
        val error = IOException("The NFS server is unreachable")
        val decoder = CurrentBindDecoder(FakeDecoder(error)) { true }
        try {
            decoder.init(context, uri)
            fail()
        } catch (e: IOException) {
            assertSame(error, e)
        }
    }

    @Test
    fun aStaleDecoderThatWasHandedOverAnywayDecodesNoTile() {
        var current = true
        val fake = FakeDecoder()
        val decoder = CurrentBindDecoder(fake) { current }
        decoder.init(context, uri)
        current = false
        try {
            decoder.decodeRegion(Rect(0, 0, 10, 10), 1)
            fail()
        } catch (e: StaleDecoderException) {
            assertEquals(0, fake.tiles)
        }
    }
}
