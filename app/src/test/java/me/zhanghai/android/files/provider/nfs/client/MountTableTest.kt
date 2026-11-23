/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.nfs.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * rc2 B3: after airplane mode a context with a file still open stayed the current one, so every
 * listing went to the dead socket until the app was restarted.
 */
class MountTableTest {
    private class FakeMount(authority: String) : MountTable.State<String>(authority)

    private val table = MountTable<String, FakeMount>()

    private fun FakeMount.open(file: Long) {
        files++
        table.addHandle(file, this)
    }

    private fun FakeMount.close(file: Long) {
        files--
        table.removeHandle(file, this)
    }

    @Test
    fun staleContextWithOpenFileIsDetachedForNewCalls() {
        val old = table.install(FakeMount("nas"))
        old.open(1L)

        assertFalse("destroy must wait for the open file", table.retire(old))
        assertTrue(old.detached)
        assertNull("new calls must mount a fresh context", table.current("nas"))

        val fresh = table.install(FakeMount("nas"))
        assertSame(fresh, table.current("nas"))
        // The open handle still runs on the context that opened it.
        assertSame(old, table.mountFor(1L))
    }

    @Test
    fun detachedContextIsDroppedWhenItsLastFileCloses() {
        table.pin("nas")
        val old = table.install(FakeMount("nas"))
        old.open(1L)
        old.open(2L)
        table.retire(old)
        table.install(FakeMount("nas"))

        old.close(1L)
        assertFalse(table.canDrop(old))
        old.close(2L)
        assertTrue("a pin of the new context must not keep the old one", table.canDrop(old))
        assertNull(table.mountFor(2L))
    }

    @Test
    fun idleContextIsDestroyedAtOnce() {
        val mount = table.install(FakeMount("nas"))
        assertTrue(table.retire(mount))
        assertNull(table.current("nas"))
    }

    @Test
    fun retiringADetachedContextDoesNotRemoveItsSuccessor() {
        val old = table.install(FakeMount("nas"))
        old.open(1L)
        table.retire(old)
        val fresh = table.install(FakeMount("nas"))
        old.close(1L)
        table.retire(old)
        assertSame(fresh, table.current("nas"))
    }

    @Test
    fun pinnedCurrentContextIsKept() {
        table.pin("nas")
        val mount = table.install(FakeMount("nas"))
        assertFalse(table.canDrop(mount))
        assertSame(mount, table.unpin("nas"))
        assertTrue(table.canDrop(mount))
    }

    @Test
    fun currentContextWithOpenFileIsKept() {
        val mount = table.install(FakeMount("nas"))
        mount.open(1L)
        assertFalse(table.canDrop(mount))
    }

    @Test
    fun secondInstallReturnsTheFirst() {
        val first = table.install(FakeMount("nas"))
        val second = FakeMount("nas")
        assertSame(first, table.install(second))
        assertEquals(listOf(first), table.currentMounts())
    }

    @Test
    fun serversAreIndependent() {
        val a = table.install(FakeMount("a"))
        val b = table.install(FakeMount("b"))
        table.retire(a)
        assertNull(table.current("a"))
        assertSame(b, table.current("b"))
    }

    @Test
    fun closingAHandleKeepsTheSamePointerOpenedOnAnotherContext() {
        val old = table.install(FakeMount("nas"))
        old.open(1L)
        table.retire(old)
        val fresh = table.install(FakeMount("nas"))
        // nfs_close freed the handle and the other context's open got the same address
        // before the close was booked.
        fresh.open(1L)
        old.close(1L)
        assertSame(fresh, table.mountFor(1L))
    }
}
