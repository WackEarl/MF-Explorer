/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.smb

import me.zhanghai.android.files.provider.smb.client.Authority
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class SmbUriTest {
    private val fileSystems = mutableSetOf<SmbFileSystem>()

    private fun path(path: String): SmbPath =
        SmbFileSystemProvider.getOrNewFileSystem(
            Authority("10.0.2.2", Authority.DEFAULT_PORT, "user", null)
        ).also { fileSystems += it }.getPath(path)

    @After
    fun closeFileSystems() {
        fileSystems.forEach { it.close() }
    }

    @Test
    fun specialCharactersInPathRoundTrip() {
        for (name in listOf(
            "/share/Urlaub 2026/Bäume #1?.jpg",
            "/share/50%/a+b=c&d",
            "/share/ampersand & hash # plus + percent %.png",
            "/share/space in name.jpg"
        )) {
            val smbPath = path(name)
            val parsed = SmbFileSystemProvider.getPath(smbPath.toUri()) as SmbPath
            fileSystems += parsed.getFileSystem() as SmbFileSystem
            assertEquals(smbPath, parsed)
            assertEquals(name, parsed.toString())
        }
    }
}
