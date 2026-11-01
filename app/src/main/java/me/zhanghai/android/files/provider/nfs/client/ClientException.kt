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
    fun toFileSystemException(file: String?, other: String? = null): FileSystemException =
        when (errno) {
            OsConstants.ENOENT -> NoSuchFileException(file, other, message)
            OsConstants.EEXIST -> FileAlreadyExistsException(file, other, message)
            OsConstants.EACCES, OsConstants.EPERM -> AccessDeniedException(file, other, message)
            OsConstants.EROFS -> ReadOnlyFileSystemException(file, other, message)
            OsConstants.ENOTEMPTY -> DirectoryNotEmptyException(file)
            OsConstants.ENOTDIR -> NotDirectoryException(file)
            OsConstants.EINVAL, OsConstants.ENAMETOOLONG ->
                InvalidFileNameException(file, other, message)
            OsConstants.ELOOP -> FileSystemLoopException(file)
            OsConstants.EISDIR -> IsDirectoryException(file, other, message)
            else -> FileSystemException(file, other, message)
        }.apply { initCause(this@ClientException) }
}
