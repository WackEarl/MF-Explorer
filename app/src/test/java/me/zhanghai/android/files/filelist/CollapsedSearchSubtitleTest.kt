/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollapsedSearchSubtitleTest {
    @Test
    fun showsWhenSearchViewIsCollapsedAndQueryIsNotEmpty() {
        assertTrue(CollapsedSearchSubtitle.shouldShow(isSearchViewExpanded = false, query = "test"))
    }

    @Test
    fun hidesWhenSearchViewIsExpanded() {
        assertFalse(CollapsedSearchSubtitle.shouldShow(isSearchViewExpanded = true, query = "test"))
    }

    @Test
    fun hidesWhenQueryIsEmpty() {
        assertFalse(CollapsedSearchSubtitle.shouldShow(isSearchViewExpanded = false, query = ""))
    }

    @Test
    fun prefersSearchViewQuery() {
        assertEquals(
            "test",
            CollapsedSearchSubtitle.query(
                searchViewQuery = "test",
                deepSearchQuery = "other",
                isDeepSearching = true
            )
        )
    }

    @Test
    fun fallsBackToDeepSearchQuery() {
        assertEquals(
            "report",
            CollapsedSearchSubtitle.query(
                searchViewQuery = "",
                deepSearchQuery = "report",
                isDeepSearching = true
            )
        )
    }

    @Test
    fun isEmptyWhenCollapsedWithoutQuery() {
        assertEquals(
            "",
            CollapsedSearchSubtitle.query(
                searchViewQuery = "",
                deepSearchQuery = "",
                isDeepSearching = false
            )
        )
    }
}
