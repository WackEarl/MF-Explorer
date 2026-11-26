/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import android.system.OsConstants
import kotlinx.coroutines.CancellationException
import me.zhanghai.android.files.provider.nfs.client.ClientException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException

/** Issue #61: expected failures do not flood the log with stack traces. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FailureLogTest {
    private val offline = ClientException(OsConstants.ENETUNREACH, "Network is unreachable")
        .toFileSystemException("/img-07.jpg")

    @Before
    fun setUp() {
        ShadowLog.clear()
    }

    private fun logs() = ShadowLog.getLogsForTag(TAG)

    @Test
    fun aCancelledLoadIsNotLogged() {
        CancellationException("swiped on").logFailure(TAG, "Cannot load")
        InterruptedIOException().logFailure(TAG, "Cannot load")

        assertEquals(0, logs().size)
    }

    @Test
    fun anOfflineErrorIsOneLineWithoutAStackTrace() {
        offline.logFailure(TAG, "Cannot load /img-07.jpg")
        IOException("connect", ConnectException("refused")).logFailure(TAG, "Cannot load")
        SocketTimeoutException("read timed out").logFailure(TAG, "Cannot load")

        val logs = logs()
        assertEquals(3, logs.size)
        logs.forEach { assertNull("stack trace logged for ${it.msg}", it.throwable) }
        assertTrue(logs[0].msg, logs[0].msg.startsWith("Cannot load /img-07.jpg: "))
        assertTrue(logs[0].msg, logs[0].msg.contains("The NFS server is unreachable"))
    }

    @Test
    fun anUnexpectedFailureKeepsItsStackTrace() {
        val e = IllegalStateException("BitmapFactory returned null")
        e.logFailure(TAG, "Cannot load")
        IOException("Broken pipe").logFailure(TAG, "Cannot load")

        val logs = logs()
        assertEquals(2, logs.size)
        assertEquals(e, logs[0].throwable)
        assertNotNull(logs[1].throwable)
    }

    companion object {
        private const val TAG = "FailureLogTest"
    }
}
