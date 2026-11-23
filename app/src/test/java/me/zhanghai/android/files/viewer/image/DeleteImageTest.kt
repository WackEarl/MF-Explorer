/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import android.system.OsConstants
import java8.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.zhanghai.android.files.provider.nfs.client.ClientException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/** Issue #52: the viewer's delete runs off the calling thread and shows a readable error. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeleteImageTest {
    private val path = Proxy.newProxyInstance(
        Path::class.java.classLoader, arrayOf(Path::class.java)
    ) { _, method, _ ->
        when (method.name) {
            "toString" -> "nfs://192.0.2.1/export/a.jpg"
            else -> throw AssertionError(method.name)
        }
    } as Path

    @Test
    fun deletesOnTheIoDispatcher() = runBlocking {
        val caller = Thread.currentThread()
        var deleter: Thread? = null

        val message = deleteImage(path, Dispatchers.IO) { deleter = Thread.currentThread() }

        assertNull(message)
        assertNotSame(caller, deleter)
    }

    @Test
    fun nfsErrorShowsOnlyTheReason() = runBlocking {
        val message = deleteImage(path, Dispatchers.IO) {
            throw ClientException(
                OsConstants.ENETUNREACH, "connect() to server failed. Network is unreachable(101)"
            ).toFileSystemException(it.toString())
        }

        assertEquals("The NFS server is unreachable", message)
    }
}
