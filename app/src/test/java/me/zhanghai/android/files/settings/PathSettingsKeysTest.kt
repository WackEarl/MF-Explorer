/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.app.Application
import android.content.Context
import me.zhanghai.android.files.provider.nfs.NfsFileSystem
import me.zhanghai.android.files.provider.nfs.NfsFileSystemProvider
import me.zhanghai.android.files.provider.nfs.client.Authority
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PathSettingsKeysTest {
    private val fileSystems = mutableSetOf<NfsFileSystem>()

    private fun nfsPath(
        host: String,
        export: String,
        path: String,
        uid: Int = 65534,
        gid: Int = 65534
    ) = NfsFileSystemProvider.getOrNewFileSystem(
        Authority(host, Authority.PORTMAPPER, export, uid, gid)
    ).also { fileSystems += it }.getPath(path)

    @After
    fun closeFileSystems() {
        fileSystems.forEach { it.close() }
        fileSystems.clear()
    }

    @Test
    fun sha256HexIsLowercaseAndStable() {
        val first = PathSettingsKeys.sha256Hex("nfs://10.0.2.2/media?export=%2Fexport")
        val second = PathSettingsKeys.sha256Hex("nfs://10.0.2.2/media?export=%2Fexport")
        assertEquals(64, first.length)
        assertTrue(first.matches(Regex("^[0-9a-f]{64}$")))
        assertEquals(first, second)
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            PathSettingsKeys.sha256Hex("")
        )
    }

    @Test
    fun suffixMatchesSha256OfCanonicalUri() {
        val path = nfsPath("10.0.2.2", "/export", "/media")
        assertEquals("nfs://10.0.2.2/media?export=%2Fexport", PathSettingsKeys.canonicalUri(path))
        assertEquals(
            PathSettingsKeys.sha256Hex(path.toUri().toString()),
            PathSettingsKeys.suffixFor(path)
        )
    }

    @Test
    fun nfsSharesWithTheSameRemotePathHaveDifferentSuffixes() {
        val first = nfsPath("10.0.2.2", "/export", "/media")
        val second = nfsPath("192.168.0.233", "/export", "/media")
        val third = nfsPath("10.0.2.2", "/mnt/cache/mfx-nfstest", "/media")
        assertEquals("/media", first.toString())
        assertEquals("/media", second.toString())
        assertEquals("/media", third.toString())
        assertNotEquals(PathSettingsKeys.suffixFor(first), PathSettingsKeys.suffixFor(second))
        assertNotEquals(PathSettingsKeys.suffixFor(first), PathSettingsKeys.suffixFor(third))
        assertNotEquals(
            PathSettingsKeys.canonicalUri(first),
            PathSettingsKeys.canonicalUri(third)
        )
    }

    @Test
    fun uidAndGidChangeTheSuffix() {
        val nobody = nfsPath("10.0.2.2", "/export", "/media")
        val user = nfsPath("10.0.2.2", "/export", "/media", uid = 1000, gid = 1000)
        assertNotEquals(PathSettingsKeys.suffixFor(nobody), PathSettingsKeys.suffixFor(user))
    }

    @Test
    fun migratableLocalPaths() {
        assertTrue(PathSettingsKeys.isMigratableLocalPath("/storage/emulated/0/DCIM"))
        assertTrue(PathSettingsKeys.isMigratableLocalPath("/sdcard/Download"))
        assertTrue(PathSettingsKeys.isMigratableLocalPath("/sdcard"))
        assertTrue(PathSettingsKeys.isMigratableLocalPath("/mnt/media_rw/ABCD-1234"))
        assertFalse(PathSettingsKeys.isMigratableLocalPath("/"))
        assertFalse(PathSettingsKeys.isMigratableLocalPath("/media"))
        assertFalse(PathSettingsKeys.isMigratableLocalPath("/export"))
        assertFalse(PathSettingsKeys.isMigratableLocalPath("media"))
    }

    @Test
    fun legacyLocalSuffixBecomesFileUri() {
        val canonical = PathSettingsKeys.canonicalUriForLegacySuffix("/storage/emulated/0/DCIM")
        assertEquals("file:///storage/emulated/0/DCIM", canonical)
    }

    @Test
    fun legacyRemoteSuffixIsDropped() {
        assertNull(PathSettingsKeys.canonicalUriForLegacySuffix("/media"))
        assertNull(PathSettingsKeys.canonicalUriForLegacySuffix("/"))
        assertNull(PathSettingsKeys.canonicalUriForLegacySuffix("DCIM"))
        assertNull(
            PathSettingsKeys.canonicalUriForLegacySuffix(
                PathSettingsKeys.sha256Hex("file:///storage/emulated/0/DCIM")
            )
        )
    }

    @Test
    fun migrateRewritesLocalKeysAndDropsRemoteOnes() {
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("path-settings-keys-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val sortPrefix = "key_file_list_sort_options"
        val viewPrefix = "key_file_list_view_type"
        val localSortKey = "${sortPrefix}_/storage/emulated/0/DCIM"
        val remoteSortKey = "${sortPrefix}_/media"
        val alreadyHashed = "${sortPrefix}_" + PathSettingsKeys.sha256Hex("already")
        prefs.edit()
            .putString(localSortKey, "sort-parcel")
            .putString("${viewPrefix}_/storage/emulated/0/DCIM", "1")
            .putString(remoteSortKey, "nfs-sort")
            .putString(alreadyHashed, "keep")
            .commit()

        val changed = PathSettingsKeys.migrateLegacyKeys(prefs, sortPrefix, viewPrefix)
        assertEquals(3, changed)

        val expectedSort = "${sortPrefix}_" +
            PathSettingsKeys.sha256Hex("file:///storage/emulated/0/DCIM")
        val expectedView = "${viewPrefix}_" +
            PathSettingsKeys.sha256Hex("file:///storage/emulated/0/DCIM")
        assertEquals("sort-parcel", prefs.getString(expectedSort, null))
        assertEquals("1", prefs.getString(expectedView, null))
        assertFalse(prefs.contains(localSortKey))
        assertFalse(prefs.contains("${viewPrefix}_/storage/emulated/0/DCIM"))
        assertFalse(prefs.contains(remoteSortKey))
        assertEquals("keep", prefs.getString(alreadyHashed, null))
    }

    @Test
    fun migrateDoesNotOverwriteAnExistingHashedKey() {
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("path-settings-keys-test-keep", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val sortPrefix = "key_file_list_sort_options"
        val hashed = "${sortPrefix}_" +
            PathSettingsKeys.sha256Hex("file:///storage/emulated/0/DCIM")
        prefs.edit()
            .putString(hashed, "new-value")
            .putString("${sortPrefix}_/storage/emulated/0/DCIM", "old-value")
            .commit()

        PathSettingsKeys.migrateLegacyKeys(prefs, sortPrefix)
        assertEquals("new-value", prefs.getString(hashed, null))
        assertFalse(prefs.contains("${sortPrefix}_/storage/emulated/0/DCIM"))
    }

    @Test
    fun migrateIsIdempotent() {
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("path-settings-keys-test-twice", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val sortPrefix = "key_file_list_sort_options"
        prefs.edit().putString("${sortPrefix}_/storage/emulated/0/Pictures", "v").commit()
        assertEquals(1, PathSettingsKeys.migrateLegacyKeys(prefs, sortPrefix))
        assertEquals(0, PathSettingsKeys.migrateLegacyKeys(prefs, sortPrefix))
        assertEquals(1, prefs.all.size)
    }
}
