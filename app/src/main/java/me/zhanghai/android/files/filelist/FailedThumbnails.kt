/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import java8.nio.file.Path
import java.io.IOException

/**
 * Remote thumbnails whose read failed, e.g. while the share was unreachable. Nothing asks for a
 * failed thumbnail again until its item is bound again, so these are retried once the share may
 * be back. Thumbnails that loaded or failed for another reason are left alone.
 */
internal class FailedThumbnails {
    private val paths = mutableSetOf<Path>()

    /** Called when an item binds and starts a new load for [path]. */
    fun onLoadStarted(path: Path) {
        paths.remove(path)
    }

    fun onLoadFailed(path: Path, throwable: Throwable) {
        // Not e.g. a file that cannot be decoded, or reading remote files for thumbnails being
        // turned off: retrying those would fail the same way.
        if (path.isRemotePath && throwable is IOException) {
            paths.add(path)
        }
    }

    /**
     * @return the positions of the failed thumbnails that are still in the list, whose items
     * should be bound again. Forgets every failure; a retry that fails again is added back.
     */
    fun takeRetryPositions(positionOf: (Path) -> Int?): List<Int> {
        val positions = paths.mapNotNull(positionOf).sorted()
        paths.clear()
        return positions
    }
}
