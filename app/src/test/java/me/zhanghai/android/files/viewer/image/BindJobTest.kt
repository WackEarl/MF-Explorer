/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Issue #53: a page that is recycled or rebound must not show the result of its old load. */
class BindJobTest {
    @Test
    fun rebindCancelsTheOldLoadBeforeItShowsAnything() = runBlocking {
        val bindJob = BindJob()
        val shown = mutableListOf<String>()
        val oldReadStarted = CompletableDeferred<Unit>()
        val oldReadMayFinish = CountDownLatch(1)

        val old = bindJob.launch(this) {
            // Like loadImageInfo(): a blocking read that cannot be interrupted.
            val info = withContext(Dispatchers.IO) {
                oldReadStarted.complete(Unit)
                oldReadMayFinish.await(5, TimeUnit.SECONDS)
                "old"
            }
            shown += info
        }
        oldReadStarted.await()
        val new = bindJob.launch(this) { shown += "new" }
        oldReadMayFinish.countDown()
        withTimeout(5000) {
            old.join()
            new.join()
        }

        assertTrue(old.isCancelled)
        assertEquals(listOf("new"), shown)
    }

    @Test
    fun recycleCancelsTheLoad() = runBlocking {
        val bindJob = BindJob()
        var shown = false
        val job = bindJob.launch(this) {
            withContext(Dispatchers.IO) { Thread.sleep(50) }
            shown = true
        }
        yield()
        bindJob.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(false, shown)
    }
}
