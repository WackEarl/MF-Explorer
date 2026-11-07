/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import java8.nio.file.Path

class SearchState(
    val isSearching: Boolean,
    val query: String,
    val directory: Path? = null
)
