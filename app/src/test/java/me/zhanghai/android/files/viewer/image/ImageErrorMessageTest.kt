/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import android.content.Context
import android.system.OsConstants
import kotlinx.coroutines.CancellationException
import me.zhanghai.android.files.R
import me.zhanghai.android.files.provider.nfs.client.ClientException
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

/** Issue #60: the viewer's error page shows a readable message, not the exception. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImageErrorMessageTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private val genericError: String
        get() = context.getString(R.string.image_viewer_error)

    @Test
    fun anOfflineNfsReadShowsOnlyTheReason() {
        val e = ClientException(OsConstants.ENETUNREACH, "Network is unreachable")
            .toFileSystemException("/img-07.jpg")

        assertEquals("The NFS server is unreachable", e.toImageErrorMessage(context))
    }

    @Test
    fun anotherReadErrorShowsTheGenericMessage() {
        assertEquals(genericError, IOException("Broken pipe").toImageErrorMessage(context))
    }

    @Test
    fun aCancelledReadShowsTheGenericMessage() {
        assertEquals(
            genericError, CancellationException("read cancelled").toImageErrorMessage(context)
        )
    }
}
