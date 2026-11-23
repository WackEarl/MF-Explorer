/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.testing

import android.graphics.Bitmap
import android.graphics.Color
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/** JPEGs for tests that need the Android graphics runtime (Robolectric native graphics). */
object TestJpegs {
    val TOP_LEFT = Color.RED
    val TOP_RIGHT = Color.GREEN
    val BOTTOM_LEFT = Color.BLUE
    val BOTTOM_RIGHT = Color.WHITE

    /** Four solid quadrants, so a test can tell every orientation apart. */
    fun quadrants(width: Int, height: Int, noise: Boolean = false): Bitmap {
        val pixels = IntArray(width * height)
        val random = Random(1)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val color = when {
                    y < height / 2 && x < width / 2 -> TOP_LEFT
                    y < height / 2 -> TOP_RIGHT
                    x < width / 2 -> BOTTOM_LEFT
                    else -> BOTTOM_RIGHT
                }
                pixels[y * width + x] = if (noise) addNoise(color, random) else color
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun addNoise(color: Int, random: Random): Int {
        fun channel(value: Int) = (value + random.nextInt(-40, 41)).coerceIn(0, 255)
        return Color.rgb(
            channel(Color.red(color)), channel(Color.green(color)), channel(Color.blue(color))
        )
    }

    fun encode(bitmap: Bitmap, quality: Int = 90): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            .toByteArray()

    /**
     * [main] with an EXIF APP1 segment right after SOI: IFD0 holds [orientation] (if any), IFD1
     * the JPEG [thumbnail] (if any).
     */
    fun withExif(main: ByteArray, orientation: Int?, thumbnail: ByteArray?): ByteArray {
        require(main[0] == 0xFF.toByte() && main[1] == 0xD8.toByte())
        val tiff = Buffer()
        tiff.write(byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0, 42))
        tiff.writeInt(8)
        val ifd0Entries = if (orientation != null) 1 else 0
        val ifd0Size = 2 + 12 * ifd0Entries + 4
        val ifd1Offset = if (thumbnail != null) 8 + ifd0Size else 0
        tiff.writeShort(ifd0Entries)
        if (orientation != null) {
            // Orientation, SHORT, count 1, value left-justified.
            tiff.writeShort(0x0112).writeShort(3).writeInt(1).writeShort(orientation).writeShort(0)
        }
        tiff.writeInt(ifd1Offset)
        if (thumbnail != null) {
            val thumbnailOffset = ifd1Offset + 2 + 12 * 2 + 4
            tiff.writeShort(2)
            tiff.writeShort(0x0201).writeShort(4).writeInt(1).writeInt(thumbnailOffset)
            tiff.writeShort(0x0202).writeShort(4).writeInt(1).writeInt(thumbnail.size)
            tiff.writeInt(0)
            tiff.write(thumbnail)
        }
        val payload = Buffer().apply {
            write("Exif".toByteArray())
            writeShort(0)
            writeAll(tiff)
        }
        val segmentLength = payload.size + 2
        require(segmentLength <= 0xFFFF)
        return Buffer().apply {
            write(main, 0, 2)
            writeByte(0xFF)
            writeByte(0xE1)
            writeShort(segmentLength.toInt())
            writeAll(payload)
            write(main, 2, main.size - 2)
        }.readByteArray()
    }

}

/** Counts the bytes pulled from [delegate], whoever reads them. */
class CountingSource(delegate: Source) : ForwardingSource(delegate) {
    var bytesRead = 0L
        private set

    override fun read(sink: Buffer, byteCount: Long): Long {
        val read = super.read(sink, byteCount)
        if (read > 0) {
            bytesRead += read
        }
        return read
    }
}

fun ByteArray.countingSource(): Pair<CountingSource, BufferedSource> {
    val counting = CountingSource(Buffer().write(this))
    return counting to counting.buffer()
}
