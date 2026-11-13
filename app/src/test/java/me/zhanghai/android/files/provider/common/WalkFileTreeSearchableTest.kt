/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkFileTreeSearchableTest {
    private val fileSystem = InMemoryFileSystem()
        .directory("/export/search")
        .file("/export/search/Photo1.jpg")
        .file("/export/search/notes.txt")
        .file("/export/search/sub/photo2.jpg")
        .file("/export/other/photo-outside.jpg")
        .file("/export/search-sibling/photo-sibling.jpg")
        .file("/export/photo-in-parent.jpg")

    private fun search(directory: String, query: String): List<String> {
        val results = mutableListOf<String>()
        WalkFileTreeSearchable.search(fileSystem.path(directory), query, 0) { paths ->
            results += paths.map { it.toString() }
        }
        return results
    }

    @Test
    fun findsNamesIgnoringCaseAndSkipsTheSearchedFolder() {
        val results = search("/export/search", "PHOTO")
        assertEquals(
            setOf("/export/search/Photo1.jpg", "/export/search/sub/photo2.jpg"), results.toSet()
        )
        assertEquals(results.size, results.toSet().size)
    }

    @Test
    fun searchesBreadthFirst() {
        fileSystem.file("/export/search/a/b/c/photo-deep.jpg")
        val results = search("/export/search", "photo")
        assertEquals("/export/search/a/b/c/photo-deep.jpg", results.last())
        assertTrue(results.indexOf("/export/search/Photo1.jpg") <
            results.indexOf("/export/search/sub/photo2.jpg"))
    }

    // Issue #2: a link to a parent, the root or a sibling must not widen the search.
    @Test
    fun linksThatLeaveTheSearchedFolderAreNotFollowed() {
        fileSystem
            .symbolicLink("/export/search/up", "..")
            .symbolicLink("/export/search/root", "/")
            .symbolicLink("/export/search/other", "/export/other")
            .symbolicLink("/export/search/sibling", "../search-sibling")
        val results = search("/export/search", "photo")
        assertEquals(
            setOf("/export/search/Photo1.jpg", "/export/search/sub/photo2.jpg"), results.toSet()
        )
        assertEquals(
            setOf("/export/search", "/export/search/sub"), fileSystem.listedDirectories.toSet()
        )
    }

    @Test
    fun linkIsReportedEvenWhenItsTargetIsSkipped() {
        fileSystem.symbolicLink("/export/search/photo-link", "/export/other")
        assertTrue("/export/search/photo-link" in search("/export/search", "photo-link"))
    }

    @Test
    fun linkIntoTheSearchedFolderListsEachDirectoryOnce() {
        fileSystem
            .symbolicLink("/export/search/sub-link", "sub")
            .symbolicLink("/export/search/sub/back", "..")
            .symbolicLink("/export/search/sub/abs", "/export/search/sub")
        val results = search("/export/search", "photo2")
        assertEquals(listOf("/export/search/sub/photo2.jpg"), results)
        assertEquals(1, fileSystem.listedDirectories.count { it == "/export/search/sub" })
        assertEquals(1, fileSystem.listedDirectories.count { it == "/export/search" })
    }

    @Test(timeout = 5_000)
    fun linkLoopsAndLongChainsEnd() {
        fileSystem
            .symbolicLink("/export/search/loop-a", "loop-b")
            .symbolicLink("/export/search/loop-b", "loop-a")
            .symbolicLink("/export/search/self", "self")
        for (i in 1..20) {
            fileSystem.symbolicLink("/export/search/chain$i", "chain${i + 1}")
        }
        fileSystem.symbolicLink("/export/search/chain21", "/export/other")
        val results = search("/export/search", "")
        assertTrue("/export/search/loop-a" in results)
        assertTrue("/export/search/self" in results)
        assertFalse(results.any { it.startsWith("/export/other") })
    }

    @Test
    fun danglingLinkIsReportedAndDoesNotStopTheSearch() {
        fileSystem.symbolicLink("/export/search/photo-dangling", "missing/target")
        val results = search("/export/search", "photo")
        assertTrue("/export/search/photo-dangling" in results)
        assertTrue("/export/search/sub/photo2.jpg" in results)
    }

    // An NFS link holds a server path. Mapped onto the mount, a link to the export root must
    // still count as leaving the folder.
    @Test
    fun mountMappedAbsoluteLinkStaysConfined() {
        val serverExport = "/srv/nfs".toByteString()
        fileSystem.mountMapper = { target ->
            if (target.startsWith(serverExport)) {
                fileSystem.path("/export" + target.substring(serverExport.length).toString())
            } else {
                null
            }
        }
        fileSystem
            .symbolicLink("/export/search/server-root", "/srv/nfs")
            .symbolicLink("/export/search/server-other", "/srv/nfs/other")
            .symbolicLink("/export/search/server-sub", "/srv/nfs/search/sub")
        val results = search("/export/search", "photo")
        assertEquals(
            setOf("/export/search/Photo1.jpg", "/export/search/sub/photo2.jpg"), results.toSet()
        )
        assertFalse(fileSystem.listedDirectories.any { !it.startsWith("/export/search") })
    }

    @Test
    fun unreadableFolderDoesNotStopTheSearch() {
        fileSystem.file("/export/search/locked/photo-hidden.jpg")
        fileSystem.unreadableDirectories += "/export/search/locked"
        val results = search("/export/search", "photo")
        assertTrue("/export/search/sub/photo2.jpg" in results)
        assertFalse("/export/search/locked/photo-hidden.jpg" in results)
    }

    @Test
    fun linkToAFileInsideIsReportedOnceUnderItsRealPath() {
        fileSystem.symbolicLink("/export/search/notes-link", "notes.txt")
        val results = search("/export/search", "notes")
        assertEquals(setOf("/export/search/notes.txt", "/export/search/notes-link"), results.toSet())
    }

    @Test
    fun batchesResultsByInterval() {
        val batches = mutableListOf<List<String>>()
        WalkFileTreeSearchable.search(fileSystem.path("/export/search"), "photo", 3_600_000) {
            batches += it.map { path -> path.toString() }
        }
        // The first match is published right away; the rest come in the final batch.
        assertEquals(2, batches.size)
        assertEquals(2, batches.sumOf { it.size })
    }
}
