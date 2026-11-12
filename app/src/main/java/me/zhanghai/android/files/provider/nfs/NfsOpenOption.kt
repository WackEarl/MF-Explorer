/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.nfs

import java8.nio.file.OpenOption
import java8.nio.file.Path
import me.zhanghai.android.files.provider.nfs.client.Authority

/**
 * Reads on a channel opened with [NO_READ_PIPELINE] are single synchronous READs. They neither
 * feed nor use the sequential read pipeline, so a short-lived reader such as a thumbnail cannot
 * disturb a copy that has reads in flight on the same mount. They read in smaller blocks and
 * give way to a listing, stat or other open that is waiting for the mount; their own open does
 * not make other thumbnail reads wait.
 *
 * Fixed at open because Coil reads image bytes later, on another thread.
 */
enum class NfsOpenOption : OpenOption {
    NO_READ_PIPELINE
}

/** Options for a thumbnail read of [this]. Empty unless [this] is on NFS. */
val Path.thumbnailOpenOptions: Array<OpenOption>
    get() = if (this is NfsPath) arrayOf(NfsOpenOption.NO_READ_PIPELINE) else emptyArray()

/** The mount [this] is read from, or null unless [this] is on NFS. */
internal val Path.nfsAuthority: Authority?
    get() = (this as? NfsPath)?.authority
