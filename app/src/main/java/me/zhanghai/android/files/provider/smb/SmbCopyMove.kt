/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.smb

import com.hierynomus.msdtyp.FileTime
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.protocol.commons.EnumWithValue
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.FileSystemException
import java8.nio.file.NoSuchFileException
import java8.nio.file.StandardCopyOption
import me.zhanghai.android.files.provider.common.CopyOptions
import me.zhanghai.android.files.provider.common.toByteString
import me.zhanghai.android.files.provider.smb.client.Client
import me.zhanghai.android.files.provider.smb.client.ClientException
import me.zhanghai.android.files.provider.smb.client.FileInformation
import me.zhanghai.android.files.util.enumSetOf
import me.zhanghai.android.files.util.hasBits
import java.io.IOException
import java.io.InterruptedIOException

internal object SmbCopyMove {
    @Throws(IOException::class)
    fun copy(source: SmbPath, target: SmbPath, copyOptions: CopyOptions) {
        if (copyOptions.atomicMove) {
            throw UnsupportedOperationException(StandardCopyOption.ATOMIC_MOVE.toString())
        }
        val sourceInformation = try {
            Client.getPathInformation(source, copyOptions.noFollowLinks)
        } catch (e: ClientException) {
            throw e.toFileSystemException(source.toString())
        }
        sourceInformation as? FileInformation
            ?: throw FileSystemException(source.toString(), null, "Cannot copy shares")
        val targetInformation = try {
            Client.getPathInformation(target, true)
        } catch (e: ClientException) {
            val exception = e.toFileSystemException(target.toString())
            if (exception !is NoSuchFileException) {
                throw exception
            }
            // Ignored.
            null
        }
        if (targetInformation != null) {
            targetInformation as? FileInformation
                ?: throw FileSystemException(target.toString(), null, "Cannot copy shares")
            if (SmbFileKey(source, sourceInformation.fileId)
                == SmbFileKey(target, targetInformation.fileId)) {
                copyOptions.progressListener?.invoke(sourceInformation.endOfFile)
                return
            }
            if (!copyOptions.replaceExisting) {
                throw FileAlreadyExistsException(source.toString(), target.toString(), null)
            }
            // Symbolic links may not be supported so we cannot simply delete the target here.
        }
        val sourceIsReparsePoint = sourceInformation.fileAttributes
            .hasBits(FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.value)
        val sourceIsDirectory = !sourceIsReparsePoint &&
            sourceInformation.fileAttributes.hasBits(FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value)
        val sourceIsRegularFile = !sourceIsDirectory && !sourceIsReparsePoint
        val attributesToCopy = if (copyOptions.copyAttributes) {
            EnumWithValue.EnumUtils.toEnumSet(
                sourceInformation.fileAttributes, FileAttributes::class.java
            )
        } else {
            enumSetOf(FileAttributes.FILE_ATTRIBUTE_NORMAL)
        }
        if (sourceIsRegularFile) {
            val writePath = if (targetInformation != null) target.tempCopy() else target
            try {
                Client.copyFile(
                    source, writePath, copyOptions.copyAttributes, copyOptions.noFollowLinks,
                    copyOptions.progressIntervalMillis, copyOptions.progressListener
                )
                if (writePath != target) {
                    try {
                        Client.rename(writePath, target)
                    } catch (e: ClientException) {
                        try {
                            Client.delete(writePath)
                        } catch (e2: ClientException) {
                            e.addSuppressed(e2)
                        }
                        throw e
                    }
                }
            } catch (e: ClientException) {
                if (writePath != target) {
                    try {
                        Client.delete(writePath)
                    } catch (e2: ClientException) {
                        e.addSuppressed(e2)
                    }
                }
                (e.cause as? InterruptedIOException)?.let { throw it }
                e.maybeThrowInvalidFileNameException(target.toString())
                throw e.toFileSystemException(target.toString())
            }
        } else if (sourceIsDirectory) {
            val targetIsDirectory = targetInformation != null &&
                !targetInformation.fileAttributes
                    .hasBits(FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.value) &&
                targetInformation.fileAttributes
                    .hasBits(FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value)
            if (targetInformation != null && !targetIsDirectory) {
                val backup = target.tempCopy()
                try {
                    Client.rename(target, backup)
                } catch (e: ClientException) {
                    throw e.toFileSystemException(target.toString())
                }
                try {
                    Client.createDirectory(target, attributesToCopy)
                    try {
                        Client.delete(backup)
                    } catch (e: ClientException) {
                        e.printStackTrace()
                    }
                } catch (e: ClientException) {
                    try {
                        Client.rename(backup, target)
                    } catch (e2: ClientException) {
                        e.addSuppressed(e2)
                    }
                    e.maybeThrowInvalidFileNameException(target.toString())
                    throw e.toFileSystemException(target.toString())
                }
            } else {
                try {
                    Client.createDirectory(target, attributesToCopy)
                } catch (e: ClientException) {
                    val exception = e.toFileSystemException(target.toString())
                    if (!targetIsDirectory || exception !is FileAlreadyExistsException) {
                        e.maybeThrowInvalidFileNameException(target.toString())
                        throw exception
                    }
                }
            }
            copyOptions.progressListener?.invoke(sourceInformation.endOfFile)
        } else if (sourceIsReparsePoint) {
            val sourceReparseData = try {
                Client.readSymbolicLink(source)
            } catch (e: ClientException) {
                throw e.toFileSystemException(source.toString())
            }
            try {
                Client.createSymbolicLink(target, sourceReparseData, attributesToCopy)
            } catch (e: ClientException) {
                val exception = e.toFileSystemException(target.toString())
                if (exception is FileAlreadyExistsException && copyOptions.replaceExisting) {
                    try {
                        Client.delete(target)
                    } catch (e2: ClientException) {
                        if (e2.toFileSystemException(target.toString()) !is NoSuchFileException) {
                            e2.addSuppressed(exception)
                            throw e2.toFileSystemException(target.toString())
                        }
                    }
                    try {
                        Client.createSymbolicLink(target, sourceReparseData, attributesToCopy)
                    } catch (e2: ClientException) {
                        e2.addSuppressed(exception)
                        throw e2.toFileSystemException(target.toString())
                    }
                }
                e.maybeThrowInvalidFileNameException(target.toString())
                throw e.toFileSystemException(target.toString())
            }
            copyOptions.progressListener?.invoke(sourceInformation.endOfFile)
        } else {
            throw AssertionError()
        }
        // We don't take error when copying attribute fatal, so errors will only be logged from now
        // on.
        // TODO: Copy SecurityDescriptor.
        // TODO: Change modified time last?
        try {
            val fileInformation = FileBasicInformation(
                if (copyOptions.copyAttributes) sourceInformation.creationTime else FileTime(0),
                if (copyOptions.copyAttributes) sourceInformation.lastAccessTime else FileTime(0),
                sourceInformation.lastWriteTime,
                if (copyOptions.copyAttributes) sourceInformation.changeTime else FileTime(0), 0
            )
            Client.setFileInformation(target, true, fileInformation)
        } catch (e: ClientException) {
            e.printStackTrace()
        }
        // TODO: Copy FileFullEaInformation.
    }

    @Throws(IOException::class)
    fun move(source: SmbPath, target: SmbPath, copyOptions: CopyOptions) {
        val sourceInformation = try {
            Client.getPathInformation(source, true)
        } catch (e: ClientException) {
            throw e.toFileSystemException(source.toString())
        }
        sourceInformation as? FileInformation
            ?: throw FileSystemException(source.toString(), null, "Cannot move shares")
        val targetInformation = try {
            Client.getPathInformation(target, true)
        } catch (e: ClientException) {
            val exception = e.toFileSystemException(target.toString())
            if (exception !is NoSuchFileException) {
                throw exception
            }
            // Ignored.
            null
        }
        if (targetInformation != null) {
            targetInformation as? FileInformation
                ?: throw FileSystemException(target.toString(), null, "Cannot move shares")
            if (SmbFileKey(source, sourceInformation.fileId)
                == SmbFileKey(target, targetInformation.fileId)) {
                copyOptions.progressListener?.invoke(sourceInformation.endOfFile)
                return
            }
            if (!copyOptions.replaceExisting) {
                throw FileAlreadyExistsException(source.toString(), target.toString(), null)
            }
        }
        var renameSuccessful = false
        try {
            Client.rename(source, target)
            renameSuccessful = true
        } catch (e: ClientException) {
            if (copyOptions.atomicMove) {
                e.maybeThrowAtomicMoveNotSupportedException(source.toString(), target.toString())
                e.maybeThrowInvalidFileNameException(target.toString())
                throw e.toFileSystemException(source.toString(), target.toString())
            }
            // Ignored.
        }
        if (renameSuccessful) {
            copyOptions.progressListener?.invoke(sourceInformation.endOfFile)
            return
        }
        if (copyOptions.atomicMove) {
            throw AssertionError()
        }
        var copyOptions = copyOptions
        if (!copyOptions.copyAttributes || !copyOptions.noFollowLinks) {
            copyOptions = CopyOptions(
                copyOptions.replaceExisting, true, false, true, copyOptions.progressIntervalMillis,
                copyOptions.progressListener
            )
        }
        copy(source, target, copyOptions)
        try {
            Client.delete(source)
        } catch (e: ClientException) {
            if (e.toFileSystemException(source.toString()) !is NoSuchFileException) {
                try {
                    Client.delete(target)
                } catch (e2: ClientException) {
                    e.addSuppressed(e2.toFileSystemException(target.toString()))
                }
            }
            throw e.toFileSystemException(source.toString())
        }
    }

    private fun SmbPath.tempCopy(): SmbPath {
        val name = fileNameByteString?.toString().orEmpty()
        return resolveSibling(".$name.mftmp${System.nanoTime()}".toByteString())
    }
}
