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
    isAppend: Boolean,
    private val deleteOnClose: Boolean
) : AbstractFileByteChannel(isAppend) {
    private var written = false

    override fun onReadAsync(position: Long, size: Int, timeoutMillis: Long): Future<ByteBuffer> =
        CompletedFuture(onRead(position, size))

    @Throws(IOException::class)
    override fun onRead(position: Long, size: Int): ByteBuffer {
        if (size == 0) {
            return ByteBuffer.allocate(0)
        }
        val bytes = ByteArray(size)
        val read = try {
            Client.pread(path, file, bytes, position)
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
        val remaining = source.remaining()
        val bytes = ByteArray(remaining)
        source.get(bytes)
        val writtenBytes = try {
            Client.pwrite(path, file, bytes, position)
        } catch (e: ClientException) {
            throw e.toFileSystemException(path.toString())
        }
        if (writtenBytes < remaining) {
            throw ClientException(android.system.OsConstants.EIO, "Short NFS write")
                .toFileSystemException(path.toString())
        }
        written = true
    }

    @Throws(IOException::class)
    override fun onTruncate(size: Long) {
        try {
            Client.ftruncate(path, file, size)
        } catch (e: ClientException) {
            throw e.toFileSystemException(path.toString())
        }
        written = true
    }

    @Throws(IOException::class)
    override fun onSize(): Long =
        try {
            Client.fstatSize(path, file)
        } catch (e: ClientException) {
            throw e.toFileSystemException(path.toString())
        }

    @Throws(IOException::class)
    override fun onForce(metaData: Boolean) {
        try {
            Client.fsync(path, file)
        } catch (e: ClientException) {
            throw e.toFileSystemException(path.toString())
        }
    }

    @Throws(IOException::class)
    override fun onClose() {
        try {
            Client.closeFile(path, file, deleteOnClose)
        } catch (e: ClientException) {
            throw e.toFileSystemException(path.toString())
        }
        if (written) {
            LocalWatchService.onEntryModified(path)
        }
    }

    private class CompletedFuture<T>(private val value: T) : Future<T> {
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false

        override fun isCancelled(): Boolean = false

        override fun isDone(): Boolean = true

        override fun get(): T = value

        override fun get(timeout: Long, unit: TimeUnit): T = value
    }
}
