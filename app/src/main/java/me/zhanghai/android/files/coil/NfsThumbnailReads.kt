/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.coil

import java8.nio.file.OpenOption
import java8.nio.file.Path
import kotlinx.coroutines.sync.Semaphore
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.nfs.client.Authority
import me.zhanghai.android.files.provider.nfs.nfsAuthority
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Caps the image thumbnails that read from one NFS mount at a time.
 *
 * Every call on a mount shares one libnfs context and its lock. Without a cap, a folder of images
 * started one read per visible item, and a listing of the next folder queued behind all of them.
 * Waiting for a permit suspends, so a request that is cancelled while it waits never touches the
 * mount. Coil reads the image bytes after fetch() returns, so the permit is held until it closes
 * the source, which it always does once the request ends.
 */
internal object NfsThumbnailReads {
    private const val PERMITS = 2

    private val semaphores = mutableMapOf<Authority, Semaphore>()

    private fun semaphoreFor(path: Path): Semaphore? {
        val authority = path.nfsAuthority ?: return null
        return synchronized(semaphores) { semaphores.getOrPut(authority) { Semaphore(PERMITS) } }
    }

    @Throws(IOException::class)
    suspend fun newInputStream(path: Path, vararg options: OpenOption): InputStream {
        val semaphore = semaphoreFor(path) ?: return path.newInputStream(*options)
        semaphore.acquire()
        val inputStream = try {
            path.newInputStream(*options)
        } catch (t: Throwable) {
            semaphore.release()
            throw t
        }
        return PermitInputStream(inputStream, semaphore)
    }

    private class PermitInputStream(
        inputStream: InputStream,
        private val semaphore: Semaphore
    ) : FilterInputStream(inputStream) {
        private val isReleased = AtomicBoolean()

        override fun close() {
            try {
                super.close()
            } finally {
                if (isReleased.compareAndSet(false, true)) {
                    semaphore.release()
                }
            }
        }
    }
}
