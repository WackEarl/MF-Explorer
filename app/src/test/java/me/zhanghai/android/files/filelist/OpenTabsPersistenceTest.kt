/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.app.Application
import java8.nio.file.Path
import me.zhanghai.android.files.provider.nfs.NfsFileSystem
import me.zhanghai.android.files.provider.nfs.NfsFileSystemProvider
import me.zhanghai.android.files.provider.nfs.client.Authority
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OpenTabsPersistenceTest {
    private val fileSystems = mutableSetOf<NfsFileSystem>()

    private fun nfsPath(path: String): Path =
        NfsFileSystemProvider.getOrNewFileSystem(
            Authority("10.0.2.2", Authority.PORTMAPPER, "/export", 65534, 65534)
        ).also { fileSystems += it }.getPath(path)

    @After
    fun closeFileSystems() {
        fileSystems.forEach { it.close() }
        fileSystems.clear()
    }

    private class Fixture(var enabled: Boolean, var stored: OpenTabs? = null) {
        var writes = 0
        val persistence = OpenTabsPersistence(
            { enabled },
            { stored },
            {
                writes++
                stored = it
            }
        )
    }

    @Test
    fun savesTabsWhileEnabled() {
        val fixture = Fixture(enabled = true)
        val paths = listOf(nfsPath("/a"), nfsPath("/b"))
        assertTrue(fixture.persistence.save(paths, 1, picking = false))
        assertEquals(OpenTabs(paths, 1), fixture.stored)
        assertEquals(1, fixture.writes)
    }

    @Test
    fun savesNothingWhileDisabled() {
        val fixture = Fixture(enabled = false)
        assertFalse(fixture.persistence.save(listOf(nfsPath("/a")), 0, picking = false))
        assertNull(fixture.stored)
        assertEquals(0, fixture.writes)
    }

    @Test
    fun savesNothingWhilePickingOrWithoutTabs() {
        val fixture = Fixture(enabled = true)
        assertFalse(fixture.persistence.save(listOf(nfsPath("/a")), 0, picking = true))
        assertFalse(fixture.persistence.save(emptyList(), 0, picking = false))
        assertEquals(0, fixture.writes)
    }

    @Test
    fun restoresSavedTabsWhileEnabled() {
        val saved = OpenTabs(listOf(nfsPath("/a"), nfsPath("/b")), 1)
        assertEquals(saved, Fixture(enabled = true, stored = saved).persistence.restorable())
    }

    @Test
    fun restoresNothingWhileDisabledEvenIfSomethingIsStored() {
        val saved = OpenTabs(listOf(nfsPath("/a")), 0)
        assertNull(Fixture(enabled = false, stored = saved).persistence.restorable())
    }

    @Test
    fun restoresNothingFromEmptyOrMissingTabs() {
        assertNull(Fixture(enabled = true).persistence.restorable())
        assertNull(Fixture(enabled = true, stored = OpenTabs(emptyList(), 0)).persistence.restorable())
    }

    @Test
    fun purgeDeletesStoredTabsWhileDisabled() {
        val fixture = Fixture(enabled = false, stored = OpenTabs(listOf(nfsPath("/a")), 0))
        assertTrue(fixture.persistence.purgeIfDisabled())
        assertNull(fixture.stored)
        assertEquals(1, fixture.writes)
    }

    @Test
    fun purgeWritesNothingWhenThereIsNothingToDelete() {
        val fixture = Fixture(enabled = false)
        assertFalse(fixture.persistence.purgeIfDisabled())
        assertEquals(0, fixture.writes)
    }

    @Test
    fun purgeKeepsStoredTabsWhileEnabled() {
        val saved = OpenTabs(listOf(nfsPath("/a")), 0)
        val fixture = Fixture(enabled = true, stored = saved)
        assertFalse(fixture.persistence.purgeIfDisabled())
        assertNotNull(fixture.stored)
        assertEquals(0, fixture.writes)
    }

    @Test
    fun togglingOffAndOnAgainOnlyWritesWhenEnabled() {
        val fixture = Fixture(enabled = true)
        val paths = listOf(nfsPath("/a"))
        fixture.persistence.save(paths, 0, picking = false)
        fixture.enabled = false
        fixture.persistence.purgeIfDisabled()
        fixture.persistence.save(paths, 0, picking = false)
        assertNull(fixture.stored)
        assertEquals(2, fixture.writes)
        fixture.enabled = true
        fixture.persistence.save(paths, 0, picking = false)
        assertEquals(OpenTabs(paths, 0), fixture.stored)
    }
}
