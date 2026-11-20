/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchNameFilterTest {
    @Test
    fun appliesWhenQueryIsNotEmptyEvenIfSearchViewIsCollapsed() {
        assertTrue(SearchNameFilter.shouldApply(query = "test", isDeepSearching = false))
    }

    @Test
    fun doesNotApplyWhenQueryIsEmpty() {
        assertFalse(SearchNameFilter.shouldApply(query = "", isDeepSearching = false))
    }

    @Test
    fun doesNotApplyDuringDeepSearch() {
        assertFalse(SearchNameFilter.shouldApply(query = "test", isDeepSearching = true))
    }

    @Test
    fun filtersByNameCaseInsensitive() {
        val names = listOf("Test.txt", "other.md", "latest", "report")

        val filtered = names.filter { SearchNameFilter.matches(it, "te") }

        assertEquals(listOf("Test.txt", "latest"), filtered)
    }
}
