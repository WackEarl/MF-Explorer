/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import java.io.IOException
import java.nio.ByteBuffer
import java8.nio.channels.SeekableByteChannel
import java8.nio.file.OpenOption
import java8.nio.file.Path
import me.zhanghai.android.files.provider.common.newByteChannel
import me.zhanghai.android.files.provider.document.isDocumentPath
import me.zhanghai.android.files.provider.document.resolver.DocumentResolver
import me.zhanghai.android.files.provider.ftp.isFtpPath
import me.zhanghai.android.files.provider.linux.isLinuxPath

val Path.isMediaMetadataRetrieverCompatible: Boolean
    get() = !isFtpPath

/**
 * [options] and [isCancelled] only apply to a path read through a byte channel. Once
 * [isCancelled] returns true, every read fails at once, so the retriever stops instead of
 * reading a file nobody waits for any more.
 */
fun MediaMetadataRetriever.setDataSource(
    path: Path,
    vararg options: OpenOption,
    isCancelled: () -> Boolean = { false }
) {
    when {
        path.isLinuxPath -> setDataSource(path.toFile().path)
        path.isDocumentPath ->
            DocumentResolver.openParcelFileDescriptor(path as DocumentResolver.Path, "r")
                .use { pfd -> setDataSource(pfd.fileDescriptor) }
        else -> {
            val channel = try {
                path.newByteChannel(*options)
            } catch (e: IOException) {
                throw IllegalArgumentException(e)
            }
            setDataSource(PathMediaDataSource(channel, isCancelled))
        }
    }
}

private class PathMediaDataSource(
    private val channel: SeekableByteChannel,
    private val isCancelled: () -> Boolean
) : MediaDataSource() {
    @Volatile
    private var isClosed = false

    @Throws(IOException::class)
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        // The media server may still read after release() or a cancelled request. -1 ends
        // that read without another round trip.
        if (isClosed || isCancelled()) {
            return -1
        }
        channel.position(position)
        return channel.read(ByteBuffer.wrap(buffer, offset, size))
    }

    @Throws(IOException::class)
    override fun getSize(): Long {
        return channel.size()
    }

    @Throws(IOException::class)
    override fun close() {
        isClosed = true
        channel.close()
    }
}
