/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import me.zhanghai.android.files.provider.nfs.client.Authority
import me.zhanghai.android.files.provider.nfs.createNfsRootPath
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** rc2 K1: the replace dialog in the root of an NFS share said: already exists in "null". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConflictDirectoryNameTest {
    private val authority = Authority(
        "nas.example", Authority.PORTMAPPER, "/export/media", Authority.DEFAULT_UID,
        Authority.DEFAULT_GID
    )

    @Test
    fun nfsRootIsNamedAfterServerAndExport() {
        val root = authority.createNfsRootPath()
        assertEquals("nas.example/export/media", FileJobConflictDialogFragment.directoryName(root))
    }

    @Test
    fun nfsFolderKeepsItsName() {
        val folder = authority.createNfsRootPath().resolve("photos")
        assertEquals("photos", FileJobConflictDialogFragment.directoryName(folder))
    }

    @Test
    fun serverWithPortKeepsThePort() {
        val root = authority.copy(nfsPort = 2049).createNfsRootPath()
        assertEquals(
            "nas.example:2049/export/media", FileJobConflictDialogFragment.directoryName(root)
        )
    }
}
