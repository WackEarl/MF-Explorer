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

    /**
     * A [width] x [height] thumbnail with black bars around [quadrants] of [contentWidth] x
     * [contentHeight], as a camera pads the 160x120 EXIF thumbnail of a 3:2 photo.
     */
    fun letterboxed(width: Int, height: Int, contentWidth: Int, contentHeight: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        val content = quadrants(contentWidth, contentHeight)
        val pixels = IntArray(contentWidth * contentHeight)
        content.getPixels(pixels, 0, contentWidth, 0, 0, contentWidth, contentHeight)
        bitmap.setPixels(
            pixels, 0, contentWidth, (width - contentWidth) / 2, (height - contentHeight) / 2,
            contentWidth, contentHeight
        )
        return bitmap
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
     * [main] with an EXIF APP1 segment right after SOI: IFD0 holds [orientation] (if any) and a
     * pointer to an EXIF IFD with [pixelDimensions] (if any), IFD1 the JPEG [thumbnail] (if
     * any). [paddingBytes] of APP2 segments follow, pushing the frame header further back.
     */
    fun withExif(
        main: ByteArray,
        orientation: Int?,
        thumbnail: ByteArray?,
        pixelDimensions: Pair<Int, Int>? = null,
        paddingBytes: Int = 0
    ): ByteArray {
        require(main[0] == 0xFF.toByte() && main[1] == 0xD8.toByte())
        val tiff = tiff(orientation, thumbnail, pixelDimensions)
        val payload = Buffer().apply {
            write("Exif".toByteArray())
            writeShort(0)
            write(tiff)
        }
        val segmentLength = payload.size + 2
        require(segmentLength <= 0xFFFF)
        return Buffer().apply {
            write(main, 0, 2)
            writeByte(0xFF)
            writeByte(0xE1)
            writeShort(segmentLength.toInt())
            writeAll(payload)
            var padding = paddingBytes
            while (padding > 0) {
                val length = padding.coerceAtMost(60000)
                writeByte(0xFF)
                writeByte(0xE2)
                writeShort(length + 2)
                write(ByteArray(length))
                padding -= length
            }
            write(main, 2, main.size - 2)
        }.readByteArray()
    }

    /** A big-endian TIFF structure as EXIF stores it. */
    fun tiff(
        orientation: Int?,
        thumbnail: ByteArray? = null,
        pixelDimensions: Pair<Int, Int>? = null
    ): ByteArray {
        val ifd0Entries = (if (orientation != null) 1 else 0) +
            (if (pixelDimensions != null) 1 else 0)
        val ifd0Size = 2 + 12 * ifd0Entries + 4
        val exifIfdOffset = 8 + ifd0Size
        val exifIfdSize = if (pixelDimensions != null) 2 + 12 * 2 + 4 else 0
        val ifd1Offset = if (thumbnail != null) exifIfdOffset + exifIfdSize else 0
        val tiff = Buffer()
        tiff.write(byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0, 42))
        tiff.writeInt(8)
        tiff.writeShort(ifd0Entries)
        if (orientation != null) {
            // Orientation, SHORT, count 1, value left-justified.
            tiff.writeShort(0x0112).writeShort(3).writeInt(1).writeShort(orientation).writeShort(0)
        }
        if (pixelDimensions != null) {
            // Pointer to the EXIF IFD, LONG.
            tiff.writeShort(0x8769).writeShort(4).writeInt(1).writeInt(exifIfdOffset)
        }
        tiff.writeInt(ifd1Offset)
        if (pixelDimensions != null) {
            tiff.writeShort(2)
            tiff.writeShort(0xA002).writeShort(4).writeInt(1).writeInt(pixelDimensions.first)
            tiff.writeShort(0xA003).writeShort(4).writeInt(1).writeInt(pixelDimensions.second)
            tiff.writeInt(0)
        }
        if (thumbnail != null) {
            val thumbnailOffset = ifd1Offset + 2 + 12 * 2 + 4
            tiff.writeShort(2)
            tiff.writeShort(0x0201).writeShort(4).writeInt(1).writeInt(thumbnailOffset)
            tiff.writeShort(0x0202).writeShort(4).writeInt(1).writeInt(thumbnail.size)
            tiff.writeInt(0)
            tiff.write(thumbnail)
        }
        return tiff.readByteArray()
    }

    /** A lossy WebP of [bitmap] in the extended format, with an EXIF chunk at the end. */
    fun webpWithExifAtEnd(bitmap: Bitmap, orientation: Int): ByteArray {
        @Suppress("DEPRECATION")
        val simple = ByteArrayOutputStream()
            .also { bitmap.compress(Bitmap.CompressFormat.WEBP, 90, it) }.toByteArray()
        // RIFF <size> WEBP, then the chunks. The encoder may already write the extended format.
        val chunks = simple.copyOfRange(12, simple.size)
        val exif = tiff(orientation)
        val body = Buffer().apply {
            write("WEBP".toByteArray())
            if (String(chunks, 0, 4, Charsets.US_ASCII) == "VP8X") {
                write(chunks, 0, 8)
                writeByte(chunks[8].toInt() or 0x08) // EXIF present.
                write(chunks, 9, chunks.size - 9)
            } else {
                write("VP8X".toByteArray())
                writeIntLe(10)
                writeByte(0x08) // EXIF present.
                write(ByteArray(3))
                writeInt24Le(bitmap.width - 1)
                writeInt24Le(bitmap.height - 1)
                write(chunks)
            }
            write("EXIF".toByteArray())
            writeIntLe(exif.size)
            write(exif)
            if (exif.size % 2 != 0) {
                writeByte(0)
            }
        }
        return Buffer().apply {
            write("RIFF".toByteArray())
            writeIntLe(body.size.toInt())
            writeAll(body)
        }.readByteArray()
    }

    private fun Buffer.writeInt24Le(value: Int) {
        writeByte(value and 0xFF)
        writeByte((value shr 8) and 0xFF)
        writeByte((value shr 16) and 0xFF)
    }
}

/** Counts the bytes pulled from [delegate], whoever reads them. */
class CountingSource(delegate: Source) : ForwardingSource(delegate) {
    var bytesRead = 0L
        private set

    var isClosed = false
        private set

    override fun close() {
        isClosed = true
        super.close()
    }

    /**
     * The most bytes the reading buffered source held at once. Peeked bytes stay in its buffer,
     * so this is the heap a peeking read costs.
     */
    var maxBuffered = 0L
        private set

    override fun read(sink: Buffer, byteCount: Long): Long {
        val read = super.read(sink, byteCount)
        if (read > 0) {
            bytesRead += read
        }
        maxBuffered = maxOf(maxBuffered, sink.size)
        return read
    }
}

fun ByteArray.countingSource(): Pair<CountingSource, BufferedSource> {
    val counting = CountingSource(Buffer().write(this))
    return counting to counting.buffer()
}
