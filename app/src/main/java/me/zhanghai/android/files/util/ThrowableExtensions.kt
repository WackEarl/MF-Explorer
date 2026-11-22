/*
 * Copyright (c) 2022 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import java8.nio.file.FileSystemException
import me.zhanghai.android.files.provider.nfs.client.ClientException
import me.zhanghai.android.files.provider.nfs.client.nfsErrorKindOf

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
 * server is unreachable". The exception itself belongs in the log. Other errors keep
 * [toString] for now, where the class name is often the only hint at what went wrong.
 */
fun Throwable.toUserMessage(): String {
    if (findCauseByClass<ClientException>() == null) {
        return toString()
    }
    nfsErrorKindOf(this)?.let { return it.userMessage() }
    val reason = findCauseByClass<FileSystemException>()?.reason
    return reason?.takeIf { it.isNotBlank() }
        ?: message?.takeIf { it.isNotBlank() }
        ?: toString()
}
