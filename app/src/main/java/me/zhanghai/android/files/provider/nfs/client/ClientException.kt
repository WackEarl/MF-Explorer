/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.nfs.client

import android.system.OsConstants
import java8.nio.file.AccessDeniedException
import java8.nio.file.DirectoryNotEmptyException
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.FileSystemException
import java8.nio.file.FileSystemLoopException
import java8.nio.file.NoSuchFileException
import java8.nio.file.NotDirectoryException
import me.zhanghai.android.files.provider.common.InvalidFileNameException
import me.zhanghai.android.files.provider.common.IsDirectoryException
import me.zhanghai.android.files.provider.common.ReadOnlyFileSystemException

class ClientException(
    val errno: Int,
    message: String?
) : Exception(message) {
    fun toFileSystemException(file: String?, other: String? = null): FileSystemException {
        val kind = nfsErrorKind(errno, message)
        val reason = kind?.userMessage() ?: message
        return when {
            kind == NfsErrorKind.EXPORT_NOT_FOUND ->
                NoSuchFileException(file, other, reason)
            kind == NfsErrorKind.ACCESS_DENIED ||
                kind == NfsErrorKind.EXPORT_REFUSED ||
                errno == OsConstants.EACCES ||
                errno == OsConstants.EPERM ->
                AccessDeniedException(file, other, reason)
            errno == OsConstants.ENOENT -> NoSuchFileException(file, other, reason)
            errno == OsConstants.EEXIST -> FileAlreadyExistsException(file, other, reason)
            errno == OsConstants.EROFS -> ReadOnlyFileSystemException(file, other, reason)
            errno == OsConstants.ENOTEMPTY -> DirectoryNotEmptyException(file)
            errno == OsConstants.ENOTDIR -> NotDirectoryException(file)
            errno == OsConstants.EINVAL || errno == OsConstants.ENAMETOOLONG ->
                InvalidFileNameException(file, other, reason)
            errno == OsConstants.ELOOP -> FileSystemLoopException(file)
            errno == OsConstants.EISDIR -> IsDirectoryException(file, other, reason)
            else -> FileSystemException(file, other, reason)
        }.apply { initCause(this@ClientException) }
    }
}

/** Adds the AUTH_SYS identity to an access error, e.g. "... NFS3ERR_ACCES(-13) (as UID 65534, GID 65534)". */
internal fun appendCredentials(message: String?, uid: Int, gid: Int): String {
    val suffix = "(as UID $uid, GID $gid)"
    return if (message.isNullOrEmpty()) suffix else "$message $suffix"
}

/** The call failed because the connection to the server is down, not because of the file. */
internal fun ClientException.isTransportFailure(): Boolean =
    nfsErrorKind(errno, message)?.isTransportFailure == true
