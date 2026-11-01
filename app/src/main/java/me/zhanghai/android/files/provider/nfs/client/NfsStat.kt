/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.nfs.client

internal class NfsStat(values: LongArray) {
    val mode: Int = values[1].toInt()
    val size: Long = values[2]
    val uid: Int = values[3].toInt()
    val gid: Int = values[4].toInt()
    val atimeSec: Long = values[5]
    val atimeNsec: Int = values[6].toInt()
    val mtimeSec: Long = values[7]
    val mtimeNsec: Int = values[8].toInt()
    val ctimeSec: Long = values[9]
    val ctimeNsec: Int = values[10].toInt()
}
