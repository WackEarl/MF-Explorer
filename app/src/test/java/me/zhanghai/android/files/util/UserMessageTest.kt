/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import android.system.OsConstants
import java8.nio.file.AccessDeniedException
import me.zhanghai.android.files.provider.nfs.client.ClientException
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
}
