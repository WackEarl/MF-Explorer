/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.nfs

import android.system.OsConstants
import java8.nio.file.AtomicMoveNotSupportedException
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.FileSystemException
import java8.nio.file.NoSuchFileException
import java8.nio.file.StandardCopyOption
import me.zhanghai.android.files.provider.common.CopyOptions
import me.zhanghai.android.files.provider.common.PosixFileMode
import me.zhanghai.android.files.provider.common.PosixFileType
import me.zhanghai.android.files.provider.common.copyTo
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.newOutputStream
import me.zhanghai.android.files.provider.common.toInt
import me.zhanghai.android.files.provider.nfs.client.Client
import me.zhanghai.android.files.provider.nfs.client.ClientException
import me.zhanghai.android.files.provider.nfs.client.NfsStat
import java.io.IOException
import java.time.Instant

internal object NfsCopyMove {
    @Throws(IOException::class)
    fun copy(source: NfsPath, target: NfsPath, copyOptions: CopyOptions) {
        if (copyOptions.atomicMove) {
            throw UnsupportedOperationException(StandardCopyOption.ATOMIC_MOVE.toString())
        }
        val sourceAttributes = try {
            Client.stat(source, copyOptions.noFollowLinks)
        } catch (e: ClientException) {
            throw e.toFileSystemException(source.toString())
        }
        val targetAttributes = try {
            Client.stat(target, true)
        } catch (e: ClientException) {
            val exception = e.toFileSystemException(target.toString())
            if (exception !is NoSuchFileException) {
                throw exception
            }
            null
        }
        if (targetAttributes != null) {
            if (source == target) {
                copyOptions.progressListener?.invoke(sourceAttributes.size)
                return
            }
            if (!copyOptions.replaceExisting) {
                throw FileAlreadyExistsException(source.toString(), target.toString(), null)
            }
        }
        val sourceType = PosixFileType.fromMode(sourceAttributes.mode)
        val sourceMode = PosixFileMode.fromInt(sourceAttributes.mode).toInt()
        when (sourceType) {
            PosixFileType.REGULAR_FILE ->
                copyFile(source, target, sourceAttributes, sourceMode, targetAttributes, copyOptions)
            PosixFileType.DIRECTORY -> {
                if (targetAttributes != null) {
                    deleteIfExists(target)
                }
                val mode = if (copyOptions.copyAttributes) {
                    sourceMode
                } else {
                    PosixFileMode.CREATE_DIRECTORY_DEFAULT.toInt()
                }
                try {
                    Client.mkdir(target, mode)
                } catch (e: ClientException) {
                    throw e.toFileSystemException(target.toString())
                }
                copyOptions.progressListener?.invoke(sourceAttributes.size)
            }
            PosixFileType.SYMBOLIC_LINK ->
                throw FileSystemException(source.toString(), null, "Symbolic links are not created")
            else ->
                throw FileSystemException(source.toString(), null, sourceType.toString())
        }
        if (sourceType == PosixFileType.REGULAR_FILE || sourceType == PosixFileType.DIRECTORY) {
            copyAttributes(target, sourceAttributes, sourceMode, copyOptions.copyAttributes)
        }
    }

    @Throws(IOException::class)
    private fun copyFile(
        source: NfsPath,
        target: NfsPath,
        sourceAttributes: NfsStat,
        sourceMode: Int,
        targetAttributes: NfsStat?,
        copyOptions: CopyOptions
    ) {
        if (targetAttributes != null) {
            deleteIfExists(target)
        }
        val mode = if (copyOptions.copyAttributes) {
            sourceMode
        } else {
            PosixFileMode.CREATE_FILE_DEFAULT.toInt()
        }
        var flags = OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_TRUNC
        if (!copyOptions.replaceExisting) {
            flags = flags or OsConstants.O_EXCL
        }
        val sourceChannel = try {
            Client.openByteChannel(source, OsConstants.O_RDONLY, 0, false, false)
        } catch (e: ClientException) {
            throw e.toFileSystemException(source.toString())
        }
        try {
            val targetChannel = try {
                Client.openByteChannel(target, flags, mode, false, false)
            } catch (e: ClientException) {
                throw e.toFileSystemException(target.toString())
            }
            var successful = false
            try {
                sourceChannel.newInputStream().use { inputStream ->
                    targetChannel.newOutputStream().use { outputStream ->
                        inputStream.copyTo(
                            outputStream, copyOptions.progressIntervalMillis,
                            copyOptions.progressListener
                        )
                    }
                }
                successful = true
            } finally {
                try {
                    targetChannel.close()
                } catch (e: IOException) {
                    if (successful) {
                        throw e
                    }
                    e.printStackTrace()
                } finally {
                    if (!successful) {
                        try {
                            Client.delete(target)
                        } catch (e: ClientException) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        } finally {
            try {
                sourceChannel.close()
            } catch (e: IOException) {
                throw e
            }
        }
    }

    private fun copyAttributes(
        target: NfsPath,
        sourceAttributes: NfsStat,
        sourceMode: Int,
        copyAttributes: Boolean
    ) {
        try {
            if (copyAttributes) {
                Client.chmod(target, sourceMode)
                Client.chown(target, sourceAttributes.uid, sourceAttributes.gid)
            }
            val now = if (copyAttributes) null else Instant.now()
            Client.utimes(
                target,
                now?.epochSecond ?: sourceAttributes.atimeSec,
                now?.nano ?: sourceAttributes.atimeNsec,
                sourceAttributes.mtimeSec,
                sourceAttributes.mtimeNsec
            )
        } catch (e: ClientException) {
            e.printStackTrace()
        }
    }

    @Throws(IOException::class)
    fun move(source: NfsPath, target: NfsPath, copyOptions: CopyOptions) {
        val sourceAttributes = try {
            Client.stat(source, true)
        } catch (e: ClientException) {
            throw e.toFileSystemException(source.toString())
        }
        val targetAttributes = try {
            Client.stat(target, true)
        } catch (e: ClientException) {
            val exception = e.toFileSystemException(target.toString())
            if (exception !is NoSuchFileException) {
                throw exception
            }
            null
        }
        if (targetAttributes != null) {
            if (source == target) {
                copyOptions.progressListener?.invoke(sourceAttributes.size)
                return
            }
            if (!copyOptions.replaceExisting) {
                throw FileAlreadyExistsException(source.toString(), target.toString(), null)
            }
            try {
                Client.delete(target)
            } catch (e: ClientException) {
                throw e.toFileSystemException(target.toString())
            }
        }
        if (source.authority == target.authority) {
            try {
                Client.rename(source, target)
                copyOptions.progressListener?.invoke(sourceAttributes.size)
                return
            } catch (e: ClientException) {
                if (copyOptions.atomicMove) {
                    throw e.toFileSystemException(source.toString(), target.toString())
                }
            }
        } else if (copyOptions.atomicMove) {
            throw AtomicMoveNotSupportedException(
                source.toString(), target.toString(), "NFS rename stays on one server"
            )
        }
        val copyFallback = CopyOptions(
            true, true, false, true, copyOptions.progressIntervalMillis, copyOptions.progressListener
        )
        copy(source, target, copyFallback)
        try {
            Client.delete(source)
        } catch (e: ClientException) {
            if (e.toFileSystemException(source.toString()) !is NoSuchFileException) {
                try {
                    Client.delete(target)
                } catch (e2: ClientException) {
                    e.addSuppressed(e2.toFileSystemException(target.toString()))
                }
                throw e.toFileSystemException(source.toString())
            }
        }
    }

    @Throws(IOException::class)
    private fun deleteIfExists(path: NfsPath) {
        try {
            Client.delete(path)
        } catch (e: ClientException) {
            val exception = e.toFileSystemException(path.toString())
            if (exception !is NoSuchFileException) {
                throw exception
            }
        }
    }
}
