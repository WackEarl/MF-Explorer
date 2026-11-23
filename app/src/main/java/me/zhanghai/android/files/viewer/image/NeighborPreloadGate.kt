/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import java8.nio.file.Path
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * Gates the loads themselves, not only ViewPager's offscreenPageLimit: RecyclerView may bind
 * and prefetch neighbor holders even with the default limit. The selected image loads first.
 */
internal class NeighborPreloadGate(initialPath: Path?) {
    private data class State(val current: Path?, val loaded: Boolean)

    private val state = MutableStateFlow(State(initialPath, false))
    private val loadedPaths = mutableSetOf<Path>()

    /** Called on the main thread when the selected page changes. */
    fun onPageSelected(path: Path?) {
        state.value = State(path, path in loadedPaths)
    }

    /** Called on the main thread when a page loaded, successfully or not. */
    fun onPageLoaded(path: Path) {
        loadedPaths.add(path)
        if (state.value.current == path) {
            state.value = State(path, true)
        }
    }

    /** Suspends neighbor loads without occupying an IO thread or opening the file. */
    suspend fun awaitLoad(path: Path) {
        state.first { it.current == path || it.loaded }
    }
}
