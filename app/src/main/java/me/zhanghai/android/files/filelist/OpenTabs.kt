/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.os.Parcelable
import java8.nio.file.Path
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.util.ParcelableListParceler

@Parcelize
data class OpenTabs(
    val paths: @WriteWith<ParcelableListParceler> List<Path>,
    val selectedIndex: Int
) : Parcelable
