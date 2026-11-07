/*
 * Copyright (c) 2021 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.sftp

import java.io.IOException
import java.time.Instant
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.FileSystemException
import java8.nio.file.NoSuchFileException
import java8.nio.file.StandardCopyOption
import me.zhanghai.android.files.provider.common.CopyOptions
import me.zhanghai.android.files.provider.common.copyTo
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.newOutputStream
import me.zhanghai.android.files.provider.common.toByteString
import me.zhanghai.android.files.provider.sftp.client.Client
import me.zhanghai.android.files.provider.sftp.client.ClientException
import me.zhanghai.android.files.util.enumSetOf
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode

internal object SftpCopyMove {
    @Throws(IOException::class)
    fun copy(source: SftpPath, target: SftpPath, copyOptions: CopyOptions) {
        if (copyOptions.atomicMove) {
            throw UnsupportedOperationException(StandardCopyOption.ATOMIC_MOVE.toString())
        }
        val sourceAttributes = try {
            if (copyOptions.noFollowLinks) Client.lstat(source) else Client.stat(source)
        } catch (e: ClientException) {
            throw e.toFileSystemException(source.toString())
        }
        if (!sourceAttributes.has(FileAttributes.Flag.MODE)) {
            throw FileSystemException(
                source.toString(), null, "Missing SSH_FILEXFER_ATTR_PERMISSIONS"
            )
        }
        val targetAttributes = try {
            Client.lstat(target)
        } catch (e: ClientException) {
            val exception = e.toFileSystemException(target.toString())
            if (exception !is NoSuchFileException) {
                throw exception
            }
            // Ignored.
            null
        }
        val sourceSize = if (sourceAttributes.has(FileAttributes.Flag.SIZE)) {
            sourceAttributes.size
        } else {
            0
        }
        if (targetAttributes != null) {
            if (source == target) {
                copyOptions.progressListener?.invoke(sourceSize)
                return
            }
            if (!copyOptions.replaceExisting) {
                throw FileAlreadyExistsException(source.toString(), target.toString(), null)
            }
            // Symbolic links may not be supported so we cannot simply delete the target here.
        }
        val sourceType = sourceAttributes.type
        val sourceModeAttributes = FileAttributes.Builder()
            .apply {
                if (sourceAttributes.has(FileAttributes.Flag.MODE)) {
                    withPermissions(sourceAttributes.mode.mask)
                }
            }
            .build()
        when (sourceType) {
            FileMode.Type.REGULAR -> {
                val writePath = if (targetAttributes != null) target.tempCopy() else target
                val sourceInputStream = try {
                    Client.openByteChannel(source, enumSetOf(OpenMode.READ), FileAttributes.EMPTY)
                } catch (e: ClientException) {
                    throw e.toFileSystemException(source.toString())
                }.newInputStream()
                try {
                    val targetFlags = enumSetOf(
                        OpenMode.WRITE, OpenMode.TRUNC, OpenMode.CREAT, OpenMode.EXCL
                    )
                    val targetOutputStream = try {
                        Client.openByteChannel(writePath, targetFlags, sourceModeAttributes)
                    } catch (e: ClientException) {
                        throw e.toFileSystemException(writePath.toString())
                    }.newOutputStream()
                    var successful = false
                    try {
                        sourceInputStream.copyTo(
                            targetOutputStream, copyOptions.progressIntervalMillis,
                            copyOptions.progressListener
                        )
                        if (writePath != target) {
                            try {
                                replaceWithTemp(writePath, target)
                            } catch (e: ClientException) {
                                throw e.toFileSystemException(target.toString())
                            }
                        }
                        successful = true
                    } finally {
                        try {
                            targetOutputStream.close()
                        } catch (e: IOException) {
                            throw ClientException(e).toFileSystemException(target.toString())
                        } finally {
                            if (!successful) {
                                try {
                                    Client.remove(writePath)
                                } catch (e: ClientException) {
                                    e.printStackTrace()
                                }
                            }
                        }
                    }
                } finally {
                    try {
                        sourceInputStream.close()
                    } catch (e: IOException) {
                        throw ClientException(e).toFileSystemException(source.toString())
                    }
                }
            }
            FileMode.Type.DIRECTORY -> {
                if (targetAttributes != null
                    && targetAttributes.type != FileMode.Type.DIRECTORY) {
                    try {
                        Client.remove(target)
                    } catch (e: ClientException) {
                        val exception = e.toFileSystemException(target.toString())
                        if (exception !is NoSuchFileException) {
                            throw exception
                        }
                    }
                }
                try {
                    Client.mkdir(target, sourceModeAttributes)
                } catch (e: ClientException) {
                    val exception = e.toFileSystemException(target.toString())
                    if (targetAttributes?.type != FileMode.Type.DIRECTORY
                        || exception !is FileAlreadyExistsException) {
                        throw exception
                    }
                }
                copyOptions.progressListener?.invoke(sourceSize)
            }
            FileMode.Type.SYMLINK -> {
                val sourceTarget = try {
                    Client.readlink(source)
                } catch (e: ClientException) {
                    throw e.toFileSystemException(source.toString())
                }
                val linkPath = if (targetAttributes != null) target.tempCopy() else target
                try {
                    Client.symlink(linkPath, sourceTarget)
                    if (linkPath != target) {
                        try {
                            replaceWithTemp(linkPath, target)
                        } catch (e: ClientException) {
                            try {
                                Client.remove(linkPath)
                            } catch (e2: ClientException) {
                                e.addSuppressed(e2)
                            }
                            throw e.toFileSystemException(target.toString())
                        }
                    }
                } catch (e: ClientException) {
                    if (linkPath != target) {
                        try {
                            Client.remove(linkPath)
                        } catch (e2: ClientException) {
                            e.addSuppressed(e2)
                        }
                    }
                    throw e.toFileSystemException(target.toString())
                }
                copyOptions.progressListener?.invoke(sourceSize)
            }
            else -> throw FileSystemException(source.toString(), null, "type $sourceType")
        }
        // We don't take error when copying attribute fatal, so errors will only be logged from now
        // on.
        if (sourceType != FileMode.Type.SYMLINK) {
            val attributes = FileAttributes.Builder()
                .apply {
                    if (copyOptions.copyAttributes
                        && sourceAttributes.has(FileAttributes.Flag.UIDGID)) {
                        withUIDGID(sourceAttributes.uid, sourceAttributes.gid)
                    }
                    if (sourceAttributes.type != FileMode.Type.SYMLINK
                        && sourceAttributes.has(FileAttributes.Flag.MODE)) {
                        withPermissions(sourceAttributes.mode.mask)
                    }
                    if (sourceAttributes.has(FileAttributes.Flag.ACMODTIME)) {
                        withAtimeMtime(
                            if (copyOptions.copyAttributes) {
                                sourceAttributes.atime
                            } else {
                                // We cannot leave atime unchanged in SFTP, but since we've just
                                // written the file, its atime is simply now.
                                Instant.now().epochSecond
                            }, sourceAttributes.mtime
                        )
                    }
                }
                .build()
            try {
                Client.setstat(target, attributes)
            } catch (e: ClientException) {
                e.printStackTrace()
            }
        }
    }

    @Throws(IOException::class)
    fun move(source: SftpPath, target: SftpPath, copyOptions: CopyOptions) {
        val sourceAttributes = try {
            Client.lstat(source)
        } catch (e: ClientException) {
            throw e.toFileSystemException(source.toString())
        }
        if (!sourceAttributes.has(FileAttributes.Flag.MODE)) {
            throw FileSystemException(
                source.toString(), null, "Missing SSH_FILEXFER_ATTR_PERMISSIONS"
            )
        }
        val targetAttributes = try {
            Client.lstat(target)
        } catch (e: ClientException) {
            val exception = e.toFileSystemException(target.toString())
            if (exception !is NoSuchFileException) {
                throw exception
            }
            // Ignored.
            null
        }
        val sourceSize = if (sourceAttributes.has(FileAttributes.Flag.SIZE)) {
            sourceAttributes.size
        } else {
            0
        }
        if (targetAttributes != null) {
            if (source == target) {
                copyOptions.progressListener?.invoke(sourceSize)
                return
            }
            if (!copyOptions.replaceExisting) {
                throw FileAlreadyExistsException(source.toString(), target.toString(), null)
            }
            try {
                Client.rename(source, target, overwrite = true)
                copyOptions.progressListener?.invoke(sourceSize)
                return
            } catch (e: ClientException) {
                if (copyOptions.atomicMove) {
                    throw e.toFileSystemException(source.toString(), target.toString())
                }
            }
        }
        var renameSuccessful = false
        try {
            Client.rename(source, target)
            renameSuccessful = true
        } catch (e: ClientException) {
            if (copyOptions.atomicMove) {
                throw e.toFileSystemException(source.toString(), target.toString())
            }
            // Ignored.
        }
        if (renameSuccessful) {
            copyOptions.progressListener?.invoke(sourceSize)
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
            Client.remove(source)
        } catch (e: ClientException) {
            if (e.toFileSystemException(source.toString()) !is NoSuchFileException) {
                try {
                    Client.remove(target)
                } catch (e2: ClientException) {
                    e.addSuppressed(e2.toFileSystemException(target.toString()))
                }
            }
            throw e.toFileSystemException(source.toString())
        }
    }

    private fun SftpPath.tempCopy(): SftpPath {
        val name = fileNameByteString?.toString().orEmpty()
        return resolveSibling(".$name.mftmp${System.nanoTime()}".toByteString())
    }

    /** Puts [temp] in place of [target]. [target] is moved aside first if overwrite is unavailable. */
    private fun replaceWithTemp(temp: SftpPath, target: SftpPath) {
        try {
            Client.rename(temp, target, overwrite = true)
            return
        } catch (e: ClientException) {
            val backup = target.tempCopy()
            try {
                Client.rename(target, backup)
            } catch (e2: ClientException) {
                e.addSuppressed(e2)
                throw e
            }
            try {
                Client.rename(temp, target)
            } catch (e2: ClientException) {
                try {
                    Client.rename(backup, target)
                } catch (e3: ClientException) {
                    e2.addSuppressed(e3)
                }
                e.addSuppressed(e2)
                throw e
            }
            try {
                Client.remove(backup)
            } catch (e2: ClientException) {
                e2.printStackTrace()
            }
        }
    }
}
