/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.nfs.client

import me.zhanghai.android.files.provider.common.AbstractFileByteChannel
import me.zhanghai.android.files.provider.common.LocalWatchService
import me.zhanghai.android.files.provider.nfs.NfsPath
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

internal class FileByteChannel(
    private val path: NfsPath,
    private val file: Long,
    private val append: Boolean,
    private val deleteOnClose: Boolean,
    // Fixed at open. A thumbnail read must not feed or reset a copy's pipeline.
    private val pipeline: Boolean
) : AbstractFileByteChannel(append) {
    private var written = false
    private var nativeClosed = false
    private val writeBuffer = ByteArray(WRITE_BUFFER_SIZE)
    private var writeOffset = 0L
    private var writeLength = 0

    override fun onReadAsync(position: Long, size: Int, timeoutMillis: Long): Future<ByteBuffer> =
        LazyRead(position, size)

    private inner class LazyRead(
        private val position: Long,
        private val size: Int
    ) : Future<ByteBuffer> {
        private var value: ByteBuffer? = null
        private var error: IOException? = null
        private var done = false

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false

        override fun isCancelled(): Boolean = false

        override fun isDone(): Boolean = synchronized(this) { done }

        override fun get(): ByteBuffer {
            synchronized(this) {
                if (!done) {
                    try {
                        value = onRead(position, size)
                    } catch (e: IOException) {
                        error = e
                    }
                    done = true
                }
                error?.let { throw java.util.concurrent.ExecutionException(it) }
                return value!!
            }
        }

        override fun get(timeout: Long, unit: TimeUnit): ByteBuffer = get()
    }

    @Throws(IOException::class)
    override fun onRead(position: Long, size: Int): ByteBuffer {
        flushWrite()
        if (size == 0) {
            return ByteBuffer.allocate(0)
        }
        val bytes = ByteArray(size)
        val read = try {
            Client.pread(path, file, bytes, position, pipeline)
        } catch (e: ClientException) {
            throw e.toFileSystemException(path.toString())
        }
        if (read == 0) {
            return ByteBuffer.allocate(0)
        }
        return ByteBuffer.wrap(bytes, 0, read)
    }

    @Throws(IOException::class)
    override fun onWrite(position: Long, source: ByteBuffer) {
        bufferWrite(position, source)
    }

    @Throws(IOException::class)
    override fun onAppend(source: ByteBuffer) {
        bufferWrite(-1L, source)
    }

    @Throws(IOException::class)
    private fun bufferWrite(position: Long, source: ByteBuffer) {
        val appending = position < 0L
        var next = position
        while (source.hasRemaining()) {
            if (!appending && writeLength > 0 && next != writeOffset + writeLength) {
                flushWrite()
            }
            if (writeLength == 0 && !appending) {
                writeOffset = next
            }
            val count = source.remaining().coerceAtMost(writeBuffer.size - writeLength)
            if (count == 0) {
                flushWrite()
                continue
            }
            source.get(writeBuffer, writeLength, count)
            writeLength += count
            if (!appending) {
                next += count
            }
            if (writeLength == writeBuffer.size) {
                flushWrite()
            }
        }
        written = true
    }

    @Throws(IOException::class)
    private fun flushWrite() {
        while (writeLength > 0) {
            val length = writeLength
            val offset = writeOffset
            val bytes = writeBuffer.copyOf(length)
            val writtenBytes = try {
                if (append) {
                    Client.append(path, file, bytes)
                } else {
                    Client.pwrite(path, file, bytes, offset)
                }
            } catch (e: ClientException) {
                throw e.toFileSystemException(path.toString())
            }
            if (writtenBytes <= 0) {
                throw ClientException(android.system.OsConstants.EIO, "Short NFS write")
                    .toFileSystemException(path.toString())
            }
            if (writtenBytes < length) {
                System.arraycopy(writeBuffer, writtenBytes, writeBuffer, 0, length - writtenBytes)
            }
            writeLength -= writtenBytes
            if (!append) {
                writeOffset += writtenBytes
            }
        }
    }

    @Throws(IOException::class)
    override fun onTruncate(size: Long) {
        flushWrite()
        try {
            Client.ftruncate(path, file, size)
        } catch (e: ClientException) {
            throw e.toFileSystemException(path.toString())
        }
        written = true
    }

    @Throws(IOException::class)
    override fun onSize(): Long {
        val remote = try {
            Client.fstatSize(path, file)
        } catch (e: ClientException) {
            throw e.toFileSystemException(path.toString())
        }
        val pendingEnd = if (writeLength > 0) {
            if (append) remote + writeLength else writeOffset + writeLength
        } else {
            remote
        }
        return maxOf(remote, pendingEnd)
    }

    @Throws(IOException::class)
    override fun onForce(metaData: Boolean) {
        flushWrite()
        try {
            Client.fsync(path, file)
        } catch (e: ClientException) {
            throw e.toFileSystemException(path.toString())
        }
    }

    @Throws(IOException::class)
    override fun onClose() {
        if (!nativeClosed) {
            flushWrite()
            try {
                Client.closeFile(path, file, deleteOnClose)
            } catch (e: ClientException) {
                nativeClosed = true
                throw e.toFileSystemException(path.toString())
            }
            nativeClosed = true
        }
        if (written) {
            LocalWatchService.onEntryModified(path)
            written = false
        }
    }

    override fun onCloseFailed(exception: IOException): Boolean = nativeClosed

    companion object {
        private const val WRITE_BUFFER_SIZE = 1024 * 1024
    }
}
