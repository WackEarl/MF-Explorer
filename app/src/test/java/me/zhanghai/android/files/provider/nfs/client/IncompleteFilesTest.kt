/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.nfs.client

import me.zhanghai.android.files.provider.nfs.client.IncompleteFiles.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * rc1 B5: a local to NFS copy lost the connection, its cleanup delete failed for the same
 * reason, and the partial c100.zip (7,340,032 bytes) stayed on the share.
 */
class IncompleteFilesTest {
    private val files = IncompleteFiles<String, String, String>()

    private fun drain(group: String, outcome: (String) -> Outcome): List<String> {
        val seen = mutableListOf<String>()
        files.drain(group) { _, path, _ ->
            seen += path!!
            outcome(path)
        }
        return seen
    }

    @Test
    fun failedCleanupIsRepeatedOnceTheServerAnswers() {
        files.markIncomplete("nas", "/r217nn/c100.zip", "c100.zip", 102_000_000)
        assertTrue(files.deferDelete("nas", "/r217nn/c100.zip"))
        assertTrue(files.hasPending())
        // Still offline: kept.
        assertEquals(listOf("c100.zip"), drain("nas") { Outcome.RETRY })
        assertTrue(files.hasPending())
        // Back online: deleted once.
        assertEquals(listOf("c100.zip"), drain("nas") { Outcome.DONE })
        assertFalse(files.hasPending())
        assertEquals(emptyList<String>(), drain("nas") { Outcome.DONE })
    }

    @Test
    fun onlyFilesThatFailedWritingAreDeletedLater() {
        // A normal delete of some other file while offline must stay an error.
        assertFalse(files.deferDelete("nas", "/photos/keep.jpg"))
        assertFalse(files.hasPending())
    }

    @Test
    fun newWriterOfTheSameNameCancelsTheLaterDelete() {
        files.markIncomplete("nas", "/a.zip", "a.zip", 10)
        files.deferDelete("nas", "/a.zip")
        // Retry of the copy, or the user saved a new a.zip.
        files.forget("nas", "/a.zip")
        assertFalse(files.hasPending())
        assertFalse(files.deferDelete("nas", "/a.zip"))
    }

    @Test
    fun drainOnlyTouchesTheReachableServer() {
        files.markIncomplete("nas", "/a", "nas-a", 1)
        files.markIncomplete("pi", "/a", "pi-a", 1)
        files.deferDelete("nas", "/a")
        files.deferDelete("pi", "/a")
        assertEquals(listOf("nas-a"), drain("nas") { Outcome.DONE })
        assertTrue(files.hasPending())
        assertEquals(listOf("pi-a"), drain("pi") { Outcome.DONE })
        assertFalse(files.hasPending())
    }

    @Test
    fun retryStopsAndKeepsTheRest() {
        for (name in listOf("1", "2", "3")) {
            files.markIncomplete("nas", name, name, 1)
            files.deferDelete("nas", name)
        }
        assertEquals(listOf("1", "2"), drain("nas") { if (it == "2") Outcome.RETRY else Outcome.DONE })
        assertEquals(listOf("2", "3"), drain("nas") { Outcome.DONE })
        assertFalse(files.hasPending())
    }

    @Test
    fun largestWrittenEndIsKept() {
        files.markIncomplete("nas", "/a", "a", 7_340_032)
        files.markIncomplete("nas", "/a", "a", 1_048_576)
        files.deferDelete("nas", "/a")
        var maxSize = 0L
        files.drain("nas") { _, _, size ->
            maxSize = size
            Outcome.DONE
        }
        assertEquals(7_340_032L, maxSize)
    }

    @Test
    fun fileThatChangedSinceIsNotDeleted() {
        assertTrue(IncompleteFiles.isStillOurs(true, 7_340_032, 7_340_032))
        assertTrue(IncompleteFiles.isStillOurs(true, 0, 7_340_032))
        // Someone wrote a larger file of that name, or made it a directory.
        assertFalse(IncompleteFiles.isStillOurs(true, 7_340_033, 7_340_032))
        assertFalse(IncompleteFiles.isStillOurs(false, 0, 7_340_032))
    }

    @Test
    fun capacityIsBounded() {
        val small = IncompleteFiles<String, Int, Int>(capacity = 2)
        for (i in 1..3) {
            small.markIncomplete("nas", i, i, 1)
        }
        assertFalse(small.deferDelete("nas", 1))
        assertTrue(small.deferDelete("nas", 2))
        assertTrue(small.deferDelete("nas", 3))
    }

    @Test
    fun pendingDeletesAreSavedAndSurviveARestart() {
        var saved = emptyList<IncompleteFiles.Pending<String, String>>()
        files.onPendingChanged = { saved = it }
        files.markIncomplete("nas", "/r217nn/c100.zip", "c100.zip", 7_340_032)
        files.deferDelete("nas", "/r217nn/c100.zip")
        assertEquals(listOf(IncompleteFiles.Pending("nas", "/r217nn/c100.zip", 7_340_032L)), saved)

        // rc1 B5 was checked after a relaunch of the app.
        val restarted = IncompleteFiles<String, String, String>()
        restarted.restorePending(saved)
        assertTrue(restarted.hasPending())
        val seen = mutableListOf<Triple<String, String?, Long>>()
        restarted.drain("nas") { key, path, maxSize ->
            seen += Triple(key, path, maxSize)
            Outcome.DONE
        }
        assertEquals(listOf(Triple("/r217nn/c100.zip", null, 7_340_032L)), seen)
        assertFalse(restarted.hasPending())
    }

    @Test
    fun drainAndForgetUpdateTheSavedList() {
        var saved = emptyList<IncompleteFiles.Pending<String, String>>()
        files.onPendingChanged = { saved = it }
        for (name in listOf("a", "b")) {
            files.markIncomplete("nas", name, name, 1)
            files.deferDelete("nas", name)
        }
        assertEquals(2, saved.size)
        files.forget("nas", "a")
        assertEquals(listOf("b"), saved.map { it.key })
        files.drain("nas") { _, _, _ -> Outcome.DONE }
        assertEquals(emptyList<IncompleteFiles.Pending<String, String>>(), saved)
    }
}
