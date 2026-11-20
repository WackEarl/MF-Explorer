/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

/**
 * Live folder-name filter used while typing in search.
 *
 * The filter is applied whenever [query] is not empty, including after the search field has been
 * collapsed. A submitted deep search already returns matching files, so the name filter is not
 * applied on top of that list.
 */
object SearchNameFilter {
    fun shouldApply(query: String, isDeepSearching: Boolean): Boolean =
        !isDeepSearching && query.isNotEmpty()

    fun matches(name: String, query: String): Boolean =
        name.contains(query, ignoreCase = true)
}
