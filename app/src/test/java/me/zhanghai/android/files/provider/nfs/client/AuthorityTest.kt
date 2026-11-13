/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.nfs.client

import me.zhanghai.android.files.provider.common.UriAuthority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AuthorityTest {
    private fun authority(
        host: String = "192.168.0.10",
        port: Int = Authority.PORTMAPPER,
        export: String = "/mnt/user/media",
        uid: Int = Authority.DEFAULT_UID,
        gid: Int = Authority.DEFAULT_GID
    ) = Authority(host, port, export, uid, gid)

    @Test
    fun normalizeExportMakesItAbsoluteWithoutTrailingSlash() {
        assertEquals("/mnt/user/media", Authority.normalizeExport("mnt/user/media"))
        assertEquals("/mnt/user/media", Authority.normalizeExport("  /mnt/user/media//  "))
        assertEquals("/", Authority.normalizeExport("/"))
        assertEquals("/", Authority.normalizeExport("///"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun normalizeExportRejectsBlank() {
        Authority.normalizeExport("   ")
    }

    @Test
    fun normalizeHostBracketsIpv6Only() {
        assertEquals("[fd00::1]", Authority.normalizeHost("fd00::1"))
        assertEquals("[fd00::1]", Authority.normalizeHost(" [fd00::1] "))
        assertEquals("nas.local", Authority.normalizeHost(" nas.local "))
        assertEquals("10.0.2.2", Authority.normalizeHost("10.0.2.2"))
    }

    @Test
    fun ipv6WithAndWithoutBracketsIsTheSameServer() {
        val bare = authority(host = "fd00::1")
        val bracketed = authority(host = "[fd00::1]")
        assertEquals(bare, bracketed)
        assertEquals(bare.hashCode(), bracketed.hashCode())
    }

    @Test
    fun everyFieldTellsServersApart() {
        val base = authority()
        assertNotEquals(base, authority(host = "192.168.0.11"))
        assertNotEquals(base, authority(port = 2049))
        assertNotEquals(base, authority(export = "/mnt/user/other"))
        assertNotEquals(base, authority(uid = 1000))
        assertNotEquals(base, authority(gid = 1000))
    }

    @Test
    fun portmapperPortIsLeftOutOfUriAndName() {
        assertEquals(UriAuthority(null, "10.0.2.2", null), authority(host = "10.0.2.2").toUriAuthority())
        assertEquals("10.0.2.2/mnt/user/media", authority(host = "10.0.2.2").toString())
    }

    @Test
    fun explicitPortIsKeptInUriAndName() {
        val authority = authority(host = "fd00::1", port = 2049)
        assertEquals(UriAuthority(null, "[fd00::1]", 2049), authority.toUriAuthority())
        assertEquals("[fd00::1]:2049", authority.toUriAuthority().encode())
    }
}
