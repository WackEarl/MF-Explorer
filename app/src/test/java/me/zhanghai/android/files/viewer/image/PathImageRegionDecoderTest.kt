/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import java8.nio.file.Path
import me.zhanghai.android.files.testing.TestJpegs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.IOException
import java.lang.reflect.Proxy

/** The large image view reads the file itself, never through a proxy file descriptor. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PathImageRegionDecoderTest {
    private val context = RuntimeEnvironment.getApplication()

    private val path = Proxy.newProxyInstance(
        Path::class.java.classLoader, arrayOf(Path::class.java)
    ) { _, method, _ ->
        if (method.name == "toString") "image.jpg" else throw AssertionError(method.name)
    } as Path

    // Not served by anyone: a decoder that opened the URI would fail.
    private val uri = Uri.parse("content://nobody/image.jpg")

    private val jpeg = TestJpegs.encode(TestJpegs.quadrants(320, 240))

    private class TrackingStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }

    @Test
    fun readsThePathAndClosesItsStream() {
        val opened = mutableListOf<Path>()
        val stream = TrackingStream(jpeg)
        val decoder = PathImageRegionDecoder(path) { opened.add(it); stream }

        val size = decoder.init(context, uri)

        assertEquals(1, opened.size)
        assertSame(path, opened.single())
        assertEquals(320, size.x)
        assertEquals(240, size.y)
        assertTrue(stream.closed)
        assertTrue(decoder.isReady)
        val tile = decoder.decodeRegion(Rect(160, 0, 320, 120), 2)
        assertEquals(80, tile.width)
        assertEquals(60, tile.height)
        // The top right quadrant, which is green.
        val pixel = tile.getPixel(40, 30)
        assertTrue(Color.green(pixel) > 200 && Color.red(pixel) < 60 && Color.blue(pixel) < 60)
    }

    @Test
    fun aRecycledDecoderIsNotReadyAndRefusesTiles() {
        val decoder = PathImageRegionDecoder(path) { ByteArrayInputStream(jpeg) }
        decoder.init(context, uri)

        decoder.recycle()

        assertFalse(decoder.isReady)
        val error = runCatching { decoder.decodeRegion(Rect(0, 0, 10, 10), 1) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
    }

    @Test
    fun anOpenErrorIsThrownForTheViewToShow() {
        val failure = IOException("share offline")
        val decoder = PathImageRegionDecoder(path) { throw failure }

        val error = runCatching { decoder.init(context, uri) }.exceptionOrNull()

        assertSame(failure, error)
        assertFalse(decoder.isReady)
    }
}
