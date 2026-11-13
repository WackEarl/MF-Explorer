/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.os.Parcel
import me.zhanghai.android.files.app.appClassLoader
import me.zhanghai.android.files.provider.nfs.NfsFileSystemProvider
import me.zhanghai.android.files.provider.nfs.client.Authority
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Open tabs are stored as a marshalled Parcel (ParcelValueSettingLiveData) and read back after
 * the process restarts. Robolectric because the round trip needs a real Parcel.
 */
@RunWith(RobolectricTestRunner::class)
class OpenTabsTest {
    private val media = Authority("10.0.2.2", Authority.PORTMAPPER, "/export", 65534, 65534)
    private val other = Authority("nas.local", 2049, "/mnt/user", 1000, 100)
    private val fileSystems = listOf(media, other).map {
        NfsFileSystemProvider.getOrNewFileSystem(it)
    }

    @After
    fun closeFileSystems() {
        fileSystems.forEach { it.close() }
    }

    // Same steps as ParcelValueSettingLiveData: writeValue, marshall, unmarshall, readValue.
    private fun storeAndRestore(tabs: OpenTabs): OpenTabs {
        val bytes = Parcel.obtain().run {
            try {
                writeValue(tabs)
                marshall()
            } finally {
                recycle()
            }
        }
        return Parcel.obtain().run {
            try {
                unmarshall(bytes, 0, bytes.size)
                setDataPosition(0)
                readValue(appClassLoader) as OpenTabs
            } finally {
                recycle()
            }
        }
    }

    @Test
    fun nfsTabsAndSelectionSurviveStoring() {
        val tabs = OpenTabs(
            listOf(
                fileSystems[0].getPath("/media"),
                fileSystems[1].getPath("/big/Ordner mit Leerzeichen"),
                fileSystems[0].getPath("/dates")
            ),
            1
        )
        val restored = storeAndRestore(tabs)
        assertEquals(tabs, restored)
        assertEquals(1, restored.selectedIndex)
    }

    @Test
    fun restoredPathUsesTheOpenMount() {
        val restored = storeAndRestore(OpenTabs(listOf(fileSystems[1].getPath("/search")), 0))
        assertSame(fileSystems[1], restored.paths.single().fileSystem)
    }

    @Test
    fun emptyTabsSurviveStoring() {
        assertEquals(OpenTabs(emptyList(), 0), storeAndRestore(OpenTabs(emptyList(), 0)))
    }
}
