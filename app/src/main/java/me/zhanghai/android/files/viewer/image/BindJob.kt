/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The load of one view holder. Launching again or [cancel] cancels the previous load, so a
 * holder that is recycled or bound to another image never shows a stale result.
 */
internal class BindJob {
    private var job: Job? = null

    fun launch(scope: CoroutineScope, block: suspend CoroutineScope.() -> Unit): Job {
        cancel()
        return scope.launch(block = block).also { job = it }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}
