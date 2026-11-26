/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import java8.nio.file.FileSystemException
import java8.nio.file.Path
import me.zhanghai.android.files.testing.TestRemoteFiles
import org.junit.Assert.assertEquals
import org.junit.Test

/** Issue #62: thumbnails that failed offline are asked for again. */
class FailedThumbnailsTest {
    private val files = TestRemoteFiles()
    private val first = files.add("img-01.jpg", ByteArray(1)).first
    private val second = files.add("img-02.jpg", ByteArray(1)).first
    private val third = files.add("img-03.jpg", ByteArray(1)).first
    private val positions = mapOf(first to 0, second to 1, third to 2)

    private fun offline(path: Path) =
        FileSystemException(path.toString(), null, "The NFS server is unreachable")

    @Test
    fun onlyThumbnailsThatFailedToReadAreRetried() {
        val failed = FailedThumbnails()
        failed.onLoadStarted(first)
        failed.onLoadStarted(second)
        failed.onLoadStarted(third)
        failed.onLoadFailed(third, offline(third))
        failed.onLoadFailed(first, offline(first))
        // E.g. reading remote files for thumbnails is turned off, or the file cannot be decoded.
        failed.onLoadFailed(second, IllegalStateException("Cannot read for thumbnail"))

        assertEquals(listOf(0, 2), failed.takeRetryPositions { positions[it] })
    }

    @Test
    fun aRetryIsOnlyAskedForOnceAndAFailedRetryIsAddedBack() {
        val failed = FailedThumbnails()
        failed.onLoadFailed(first, offline(first))

        assertEquals(listOf(0), failed.takeRetryPositions { positions[it] })
        assertEquals(emptyList<Int>(), failed.takeRetryPositions { positions[it] })

        // The item binds again and the share is still unreachable.
        failed.onLoadStarted(first)
        failed.onLoadFailed(first, offline(first))
        assertEquals(listOf(0), failed.takeRetryPositions { positions[it] })
    }

    @Test
    fun aThumbnailThatLoadedOnRebindIsNotRetried() {
        val failed = FailedThumbnails()
        failed.onLoadFailed(first, offline(first))
        // Scrolled out and back in once the share was back.
        failed.onLoadStarted(first)

        assertEquals(emptyList<Int>(), failed.takeRetryPositions { positions[it] })
    }

    @Test
    fun aFileThatLeftTheListIsForgotten() {
        val failed = FailedThumbnails()
        failed.onLoadFailed(first, offline(first))
        failed.onLoadFailed(second, offline(second))

        assertEquals(listOf(1), failed.takeRetryPositions { if (it == second) 1 else null })
        assertEquals(emptyList<Int>(), failed.takeRetryPositions { positions[it] })
    }
}
