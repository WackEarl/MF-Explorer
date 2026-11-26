/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import java8.nio.file.Path
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.provider.common.delete
import me.zhanghai.android.files.util.logFailure
import me.zhanghai.android.files.util.toUserMessage
import java.io.IOException

/**
 * Deletes [path] off the main thread: on NFS this is a network call.
 *
 * @return null once deleted, or the message to show.
 */
internal suspend fun deleteImage(
    path: Path,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    delete: (Path) -> Unit = { it.delete(false) }
): String? =
    withContext(dispatcher) {
        try {
            delete(path)
            null
        } catch (e: IOException) {
            e.logFailure("DeleteImage", "Cannot delete $path")
            e.toUserMessage()
        }
    }
