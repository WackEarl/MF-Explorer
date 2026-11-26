/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import android.system.OsConstants
import com.topjohnwu.superuser.NoShellException
import java8.nio.file.AccessDeniedException
import me.zhanghai.android.files.provider.nfs.client.ClientException
import me.zhanghai.android.files.provider.remote.RemoteFileSystemException
import me.zhanghai.android.files.provider.root.ROOT_UNAVAILABLE_MESSAGE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** rc1 B7: error texts showed "java8.nio.file.FileSystemException: /: ...". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UserMessageTest {
    @Test
    fun nfsErrorShowsOnlyTheTranslatedReason() {
        val e = ClientException(OsConstants.ENETUNREACH, "connect() to server failed. Network is unreachable(101)")
            .toFileSystemException("/")
        assertEquals("The NFS server is unreachable", e.toUserMessage())
        assertFalse(e.toUserMessage().contains("java8."))
    }

    @Test
    fun copyDialogErrorAfterConnectionLoss() {
        val e = ClientException(OsConstants.EIO, "nfs_service failed")
            .toFileSystemException("/r217nn/c100.zip")
        assertEquals("Connection to the NFS server was lost", e.toUserMessage())
    }

    @Test
    fun unmappedNfsErrorShowsTheReasonWithoutClassName() {
        val e = ClientException(OsConstants.ENOSPC, "NFS: WRITE failed with NFS3ERR_NOSPC(-28)")
            .toFileSystemException("/big.bin")
        assertEquals("NFS: WRITE failed with NFS3ERR_NOSPC(-28)", e.toUserMessage())
    }

    @Test
    fun otherProvidersKeepToString() {
        val e = AccessDeniedException("/storage/emulated/0/Android/data")
        assertEquals(e.toString(), e.toUserMessage())
    }

    /** B6: Drawer → Root without root showed "…RemoteFileSystemException: Root isn't available". */
    @Test
    fun rootThatIsNotAvailableShowsAReadableText() {
        val e = RemoteFileSystemException(ROOT_UNAVAILABLE_MESSAGE)
        assertEquals("Root access isn't available", e.toUserMessage())
        // As it reaches the file list, wrapped by the provider.
        assertEquals("Root access isn't available", java.io.IOException(e).toUserMessage())
    }

    @Test
    fun rootThatIsNotAvailableIsTranslated() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val german = android.content.res.Configuration(context.resources.configuration)
            .apply { setLocale(java.util.Locale.GERMAN) }
        assertEquals(
            "Root-Zugriff ist nicht verfügbar",
            context.createConfigurationContext(german)
                .getString(me.zhanghai.android.files.R.string.root_unavailable)
        )
    }

    @Test
    fun deniedRootAccessShowsTheSameText() {
        val e = RemoteFileSystemException(NoShellException("Unable to create a shell!"))
        assertEquals("Root access isn't available", e.toUserMessage())
    }

    @Test
    fun otherRemoteErrorsKeepToString() {
        val e = RemoteFileSystemException("libsu binding died")
        assertEquals(e.toString(), e.toUserMessage())
    }
}
