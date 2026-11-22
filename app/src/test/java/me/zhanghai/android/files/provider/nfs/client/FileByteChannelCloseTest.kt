/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.nfs.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class FileByteChannelCloseTest {
    @Test
    fun closeFileRunsWhenFlushThrows() {
        var closed = false
        var handleClosed = false
        val flushError = IOException("flush failed")
        try {
            closeAfterFlush(
                flushWrite = { throw flushError },
                closeFile = { closed = true },
                onHandleClosed = { handleClosed = true }
            )
            fail("expected flush error")
        } catch (e: IOException) {
            assertSame(flushError, e)
            assertEquals(0, e.suppressed.size)
        }
        assertTrue(closed)
        assertTrue(handleClosed)
    }

    @Test
    fun closeErrorIsSuppressedWhenFlushAlsoThrows() {
        var handleClosed = false
        val flushError = IOException("flush failed")
        val closeError = IOException("close failed")
        try {
            closeAfterFlush(
                flushWrite = { throw flushError },
                closeFile = { throw closeError },
                onHandleClosed = { handleClosed = true }
            )
            fail("expected flush error")
        } catch (e: IOException) {
            assertSame(flushError, e)
            assertEquals(1, e.suppressed.size)
            assertSame(closeError, e.suppressed[0])
        }
        assertTrue(handleClosed)
    }

    @Test
    fun handleIsMarkedClosedWhenOnlyCloseThrows() {
        var handleClosed = false
        val closeError = IOException("close failed")
        try {
            closeAfterFlush(
                flushWrite = {},
                closeFile = { throw closeError },
                onHandleClosed = { handleClosed = true }
            )
            fail("expected close error")
        } catch (e: IOException) {
            assertSame(closeError, e)
            assertEquals(0, e.suppressed.size)
        }
        assertTrue(handleClosed)
    }

    @Test
    fun successfulCloseMarksTheHandleAndDoesNotThrow() {
        var closed = false
        var handleClosed = false
        closeAfterFlush(
            flushWrite = {},
            closeFile = { closed = true },
            onHandleClosed = { handleClosed = true }
        )
        assertTrue(closed)
        assertTrue(handleClosed)
    }
}
