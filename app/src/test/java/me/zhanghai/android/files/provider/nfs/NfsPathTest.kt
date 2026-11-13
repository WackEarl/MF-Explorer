/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.nfs

import me.zhanghai.android.files.provider.common.toByteString
import me.zhanghai.android.files.provider.nfs.client.Authority
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class NfsPathTest {
    private val fileSystems = mutableListOf<NfsFileSystem>()

    private fun fileSystem(export: String): NfsFileSystem =
        NfsFileSystemProvider.getOrNewFileSystem(
            Authority("10.0.2.2", Authority.PORTMAPPER, export, 1000, 1000)
        ).also { fileSystems += it }

    @After
    fun closeFileSystems() {
        fileSystems.forEach { it.close() }
    }

    private fun NfsFileSystem.link(target: String) =
        getPath("/media/link").mountAbsoluteLink(target.toByteString())

    @Test
    fun absoluteLinkUnderTheExportMapsOntoTheMount() {
        val fileSystem = fileSystem("/mnt/user")
        assertEquals(fileSystem.getPath("/media/big"), fileSystem.link("/mnt/user/media/big"))
    }

    @Test
    fun linkToTheExportItselfIsTheMountRoot() {
        val fileSystem = fileSystem("/mnt/user")
        assertSame(fileSystem.rootDirectory, fileSystem.link("/mnt/user"))
    }

    @Test
    fun mappedLinkIsNormalized() {
        val fileSystem = fileSystem("/mnt/user")
        assertEquals(fileSystem.getPath("/dates"), fileSystem.link("/mnt/user/media/../dates/."))
    }

    @Test
    fun linkThatOnlySharesAPrefixIsNotMapped() {
        assertNull(fileSystem("/mnt/user").link("/mnt/username/media"))
    }

    @Test
    fun linkOutsideTheExportIsNotMapped() {
        assertNull(fileSystem("/mnt/user").link("/etc/passwd"))
    }

    @Test
    fun relativeLinkIsNotMapped() {
        assertNull(fileSystem("/mnt/user").link("mnt/user/media"))
        assertNull(fileSystem("/mnt/user").link(""))
    }

    @Test
    fun rootExportLeavesAbsoluteLinksAlone() {
        // With "/" exported, server and mount paths already agree.
        assertNull(fileSystem("/").link("/media/big"))
    }

    @Test
    fun sameAuthorityReusesTheFileSystem() {
        val fileSystem = fileSystem("/mnt/user")
        val again = NfsFileSystemProvider.getOrNewFileSystem(
            Authority("10.0.2.2", Authority.PORTMAPPER, "/mnt/user", 1000, 1000)
        )
        assertSame(fileSystem, again)
    }
}
