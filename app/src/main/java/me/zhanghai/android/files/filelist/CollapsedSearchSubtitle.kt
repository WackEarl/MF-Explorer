/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

/**
 * Toolbar subtitle shown while the search field is collapsed but a query is still active.
 */
object CollapsedSearchSubtitle {
    fun shouldShow(isSearchViewExpanded: Boolean, query: String): Boolean =
        !isSearchViewExpanded && query.isNotEmpty()

    fun query(searchViewQuery: String, deepSearchQuery: String, isDeepSearching: Boolean): String {
        if (searchViewQuery.isNotEmpty()) {
            return searchViewQuery
        }
        return if (isDeepSearching) deepSearchQuery else ""
    }
}
