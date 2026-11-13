/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.nfs

import me.zhanghai.android.files.provider.nfs.client.Authority
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.URI

// Robolectric because the export, uid and gid travel in the query, built with android.net.Uri.
@RunWith(RobolectricTestRunner::class)
class NfsUriTest {
    private val fileSystems = mutableSetOf<NfsFileSystem>()

    private fun path(authority: Authority, path: String): NfsPath =
        NfsFileSystemProvider.getOrNewFileSystem(authority).also { fileSystems += it }
            .getPath(path)

    private fun parse(uri: URI): NfsPath =
        (NfsFileSystemProvider.getPath(uri) as NfsPath)
            .also { fileSystems += it.getFileSystem() as NfsFileSystem }

    @After
    fun closeFileSystems() {
        fileSystems.forEach { it.close() }
    }

    private fun assertRoundTrips(path: NfsPath) {
        val parsed = parse(path.toUri())
        assertEquals(path, parsed)
        assertEquals(path.authority, parsed.authority)
    }

    @Test
    fun defaultPortAndIdsRoundTrip() {
        val path = path(Authority("10.0.2.2", Authority.PORTMAPPER, "/export", 65534, 65534), "/media")
        val uri = path.toUri()
        assertEquals("nfs://10.0.2.2/media?export=/export", uri.toString())
        assertRoundTrips(path)
    }

    @Test
    fun portUidAndGidRoundTrip() {
        val path = path(Authority("nas.local", 2049, "/mnt/user/media", 1000, 100), "/big/a.mkv")
        val query = path.toUri().rawQuery
        assertTrue(query.contains("uid=1000"))
        assertTrue(query.contains("gid=100"))
        assertRoundTrips(path)
    }

    @Test
    fun ipv6HostRoundTrips() {
        assertRoundTrips(path(Authority("fd00::1", 2049, "/export", 65534, 65534), "/media"))
    }

    @Test
    fun specialCharactersInPathRoundTrip() {
        val authority = Authority("10.0.2.2", Authority.PORTMAPPER, "/mnt/user/Fotos", 65534, 65534)
        assertRoundTrips(path(authority, "/Urlaub 2026/Bäume #1?.jpg"))
        assertRoundTrips(path(authority, "/50%/a+b=c&d"))
    }

    @Test
    fun spaceAndUmlautInExportRoundTrip() {
        val authority = Authority("10.0.2.2", Authority.PORTMAPPER, "/mnt/user/Fotos Bäume", 65534, 65534)
        assertRoundTrips(path(authority, "/media"))
    }

    // Known bug: nfsAuthority decodes the query and then parses it again, so an export with
    // '&', '#', '+' or '%' comes back as another export ("/a & b" -> "/a", "/a+b" -> "/a b").
    // Enable once the provider reads the export from the raw query.
    @Ignore("Export with &, #, + or % does not survive the URI round trip")
    @Test
    fun reservedCharactersInExportRoundTrip() {
        for (export in listOf("/mnt/user/Fotos & Videos", "/a#b", "/a+b", "/a%20b")) {
            val authority = Authority("10.0.2.2", Authority.PORTMAPPER, export, 65534, 65534)
            assertRoundTrips(path(authority, "/media"))
        }
    }

    @Test
    fun exportFromUriIsNormalized() {
        val parsed = parse(URI("nfs://10.0.2.2/media?export=mnt/user/"))
        assertEquals("/mnt/user", parsed.authority.export)
    }

    @Test
    fun differentIdsAreDifferentMounts() {
        val nobody = parse(URI("nfs://10.0.2.2/media?export=%2Fexport"))
        val user = parse(URI("nfs://10.0.2.2/media?export=%2Fexport&uid=1000&gid=1000"))
        assertFalse(nobody == user)
        assertNotSame(nobody.getFileSystem(), user.getFileSystem())
    }

    @Test(expected = IllegalArgumentException::class)
    fun uriWithoutExportIsRejected() {
        parse(URI("nfs://10.0.2.2/media"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun uriWithOtherSchemeIsRejected() {
        parse(URI("smb://10.0.2.2/media?export=%2Fexport"))
    }
}
