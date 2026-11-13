/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filejob

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.Observer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class FileJobProgressTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val published = mutableListOf<List<FileJobSnapshot>>()
    private val observer = Observer<List<FileJobSnapshot>> { published += it }

    @Before
    fun observe() {
        // FileJobProgress is process-wide; start each test without jobs.
        FileJobProgress.current().forEach { FileJobProgress.remove(it.id) }
        FileJobProgress.snapshots.observeForever(observer)
        published.clear()
    }

    @After
    fun stopObserving() {
        FileJobProgress.snapshots.removeObserver(observer)
        FileJobProgress.current().forEach { FileJobProgress.remove(it.id) }
    }

    private fun upsert(id: Int, progress: Int, title: CharSequence = "Copying $id") =
        FileJobProgress.upsert(id, title, null, 100, progress, false, true)

    @Test
    fun upsertPublishesASnapshot() {
        FileJobProgress.upsert(1, StringBuilder("Copying"), StringBuilder("a.jpg"), 10, 3, false, true)
        val expected = FileJobSnapshot(1, "Copying", "a.jpg", 10, 3, false, true)
        assertEquals(listOf(expected), FileJobProgress.current())
        assertEquals(listOf(expected), published.last())
    }

    @Test
    fun progressUpdateKeepsTheJobInPlace() {
        upsert(1, 0)
        upsert(2, 0)
        upsert(1, 50)
        assertEquals(listOf(1, 2), FileJobProgress.current().map { it.id })
        assertEquals(50, FileJobProgress.current().first().progress)
    }

    @Test
    fun finishedJobIsRemoved() {
        upsert(1, 0)
        upsert(2, 0)
        FileJobProgress.remove(1)
        assertEquals(listOf(2), FileJobProgress.current().map { it.id })
        assertEquals(listOf(2), published.last().map { it.id })
        FileJobProgress.remove(2)
        // The toolbar item hides when the list is empty.
        assertTrue(FileJobProgress.current().isEmpty())
        assertTrue(published.last().isEmpty())
    }

    @Test
    fun removingAnUnknownJobPublishesNothing() {
        upsert(1, 0)
        val count = published.size
        FileJobProgress.remove(42)
        assertEquals(count, published.size)
    }

    @Test
    fun currentIsACopy() {
        upsert(1, 0)
        val before = FileJobProgress.current()
        upsert(2, 0)
        assertEquals(1, before.size)
    }
}
