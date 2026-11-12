/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future

class AbstractFileByteChannelTest {
    private class FakeChannel(
        private val data: ByteArray,
        readExactAfterSeek: Boolean = true
    ) : AbstractFileByteChannel(false, readExactAfterSeek = readExactAfterSeek) {
        /** (position, size) of every read the channel issued, prefetches included. */
        val issued = mutableListOf<Pair<Long, Int>>()

        override fun onReadAsync(
            position: Long,
            size: Int,
            timeoutMillis: Long
        ): Future<ByteBuffer> {
            issued += position to size
            val start = position.coerceAtMost(data.size.toLong()).toInt()
            val end = (position + size).coerceAtMost(data.size.toLong()).toInt()
            return CompletableFuture.completedFuture(ByteBuffer.wrap(data.copyOfRange(start, end)))
        }

        override fun onWrite(position: Long, source: ByteBuffer) = throw NotImplementedError()

        override fun onTruncate(size: Long) = throw NotImplementedError()

        override fun onSize(): Long = data.size.toLong()
    }

    private val data = ByteArray(4 * MB) { (it * 31).toByte() }

    private fun FakeChannel.readAt(position: Long, size: Int): ByteArray {
        position(position)
        val destination = ByteBuffer.allocate(size)
        val read = read(destination)
        return destination.array().copyOf(read)
    }

    private fun expected(position: Int, size: Int) = data.copyOfRange(position, position + size)

    @Test
    fun firstReadIsAFullBlockWithPrefetch() {
        val channel = FakeChannel(data)
        channel.read(ByteBuffer.allocate(8 * 1024))
        assertEquals(listOf(0L to MB, MB.toLong() to MB), channel.issued)
    }

    @Test
    fun smallSequentialReadsStayFullBlocks() {
        val channel = FakeChannel(data)
        val destination = ByteBuffer.allocate(8 * 1024)
        repeat(MB / (8 * 1024) + 1) {
            destination.clear()
            channel.read(destination)
        }
        assertEquals(
            listOf(0L to MB, MB.toLong() to MB, 2L * MB to MB), channel.issued
        )
    }

    @Test
    fun seekOutsideBufferReadsExactSizeWithoutPrefetch() {
        val channel = FakeChannel(data)
        assertArrayEquals(expected(3 * MB, 4096), channel.readAt(3L * MB, 4096))
        assertEquals(listOf(3L * MB to 4096), channel.issued)
    }

    @Test
    fun seekReadIsCappedAtOneBlock() {
        val channel = FakeChannel(data)
        val read = channel.readAt(MB.toLong(), 3 * MB)
        assertArrayEquals(expected(MB, MB), read)
        assertEquals(listOf(MB.toLong() to MB), channel.issued)
    }

    @Test
    fun adjacentReadAfterSeekIsSequential() {
        val channel = FakeChannel(data)
        channel.readAt(2L * MB, 4096)
        assertArrayEquals(expected(2 * MB + 4096, 100), channel.readAt(2L * MB + 4096, 100))
        assertEquals(
            listOf(2L * MB to 4096, 2L * MB + 4096 to MB, 3L * MB + 4096 to MB), channel.issued
        )
    }

    @Test
    fun seekInsideBufferIssuesNoRead() {
        val channel = FakeChannel(data)
        channel.readAt(0, 100)
        val before = channel.issued.toList()
        assertArrayEquals(expected(5000, 10), channel.readAt(5000, 10))
        assertArrayEquals(expected(10, 10), channel.readAt(10, 10))
        assertEquals(before, channel.issued)
    }

    @Test
    fun seekWithExactReadsDisabledKeepsFullBlocks() {
        val channel = FakeChannel(data, readExactAfterSeek = false)
        channel.readAt(3L * MB, 4096)
        assertEquals(listOf(3L * MB to MB), channel.issued.take(1))
    }

    @Test
    fun seekPastEndReturnsEndOfFile() {
        val channel = FakeChannel(data)
        channel.position(data.size + 10L)
        assertEquals(-1, channel.read(ByteBuffer.allocate(10)))
    }

    companion object {
        private const val MB = 1024 * 1024
    }
}
