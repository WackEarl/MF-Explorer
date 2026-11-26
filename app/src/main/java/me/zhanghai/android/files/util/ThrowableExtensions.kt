/*
 * Copyright (c) 2022 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import com.topjohnwu.superuser.NoShellException
import java8.nio.file.FileSystemException
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.provider.nfs.client.ClientException
import me.zhanghai.android.files.provider.nfs.client.nfsErrorKindOf
import me.zhanghai.android.files.provider.remote.RemoteFileSystemException
import me.zhanghai.android.files.provider.root.ROOT_UNAVAILABLE_MESSAGE

inline fun <reified T : Throwable> Throwable.findCauseByClass(): T? {
    var current: Throwable? = this
    do {
        if (current is T) {
            return current
        }
        current = current!!.cause
    } while (current != null)
    return null
}

/**
 * Text for an error page, dialog or toast. An NFS error shows only its translated reason, e.g.
 * "The NFS server is unreachable" instead of "java8.nio.file.FileSystemException: /: The NFS
 * server is unreachable". So does root access that is not available, e.g. "Root access isn't
 * available" when opening the root directory without root. The exception itself belongs in the
 * log. Other errors keep [toString] for now, where the class name is often the only hint at what
 * went wrong.
 */
fun Throwable.toUserMessage(): String {
    if (isRootUnavailable()) {
        return try {
            application.getString(R.string.root_unavailable)
        } catch (_: UninitializedPropertyAccessException) {
            "Root access isn't available"
        }
    }
    if (findCauseByClass<ClientException>() == null) {
        return toString()
    }
    nfsErrorKindOf(this)?.let { return it.userMessage() }
    val reason = findCauseByClass<FileSystemException>()?.reason
    return reason?.takeIf { it.isNotBlank() }
        ?: message?.takeIf { it.isNotBlank() }
        ?: toString()
}

/** No su binary, or su refused to give a shell, e.g. because the user denied root access. */
private fun Throwable.isRootUnavailable(): Boolean {
    val remote = findCauseByClass<RemoteFileSystemException>() ?: return false
    return remote.message == ROOT_UNAVAILABLE_MESSAGE ||
        remote.findCauseByClass<NoShellException>() != null
}
