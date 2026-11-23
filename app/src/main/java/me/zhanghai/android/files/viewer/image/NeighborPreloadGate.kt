/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import java8.nio.file.Path

/**
 * Lets the viewer preload neighbor pages only once the page it opened with has loaded or
 * failed. On NFS every page reads its whole file on the same mount, so preloading both
 * neighbors at once made the image the user looks at load up to three times slower.
 */
internal class NeighborPreloadGate(
    private val currentPath: () -> Path?,
    private val onOpen: () -> Unit
) {
    var isOpen = false
        private set

    /** Called when the page of [path] has finished loading, successfully or not. */
    fun onPageLoaded(path: Path) {
        if (isOpen || path != currentPath()) {
            return
        }
        isOpen = true
        onOpen()
    }
}
