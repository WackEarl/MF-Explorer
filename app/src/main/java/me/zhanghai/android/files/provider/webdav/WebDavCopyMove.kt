/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.webdav

import at.bitfire.dav4jvm.exception.DavException
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.NoSuchFileException
import java8.nio.file.StandardCopyOption
import me.zhanghai.android.files.provider.common.CopyOptions
import me.zhanghai.android.files.provider.common.copyTo
import me.zhanghai.android.files.provider.common.toByteString
import me.zhanghai.android.files.provider.webdav.client.Client
import me.zhanghai.android.files.provider.webdav.client.isDirectory
import me.zhanghai.android.files.provider.webdav.client.isSymbolicLink
import me.zhanghai.android.files.provider.webdav.client.lastModifiedTime
import me.zhanghai.android.files.provider.webdav.client.size
import java.io.IOException

internal object WebDavCopyMove {
    @Throws(IOException::class)
    fun copy(source: WebDavPath, target: WebDavPath, copyOptions: CopyOptions) {
        if (copyOptions.atomicMove) {
            throw UnsupportedOperationException(StandardCopyOption.ATOMIC_MOVE.toString())
        }
        val sourceResponse = try {
            Client.findProperties(source, copyOptions.noFollowLinks)
        } catch (e: DavException) {
            throw e.toFileSystemException(source.toString())
        }
        val targetFile = try {
            Client.findPropertiesOrNull(target, true)
        } catch (e: DavException) {
            throw e.toFileSystemException(target.toString())
        }
        val sourceSize = sourceResponse.size
        if (targetFile != null) {
            if (source == target) {
                copyOptions.progressListener?.invoke(sourceSize)
                return
            }
            if (!copyOptions.replaceExisting) {
                throw FileAlreadyExistsException(source.toString(), target.toString(), null)
            }
        }
        when {
            sourceResponse.isDirectory -> {
                if (targetFile != null && !targetFile.isDirectory) {
                    val backup = target.tempCopy()
                    try {
                        Client.move(target, backup)
                    } catch (e: DavException) {
                        throw e.toFileSystemException(target.toString())
                    }
                    try {
                        Client.makeCollection(target)
                        try {
                            Client.delete(backup)
                        } catch (e: DavException) {
                            e.printStackTrace()
                        }
                    } catch (e: DavException) {
                        try {
                            Client.move(backup, target, overwrite = true)
                        } catch (e2: DavException) {
                            e.addSuppressed(e2)
                        }
                        throw e.toFileSystemException(target.toString())
                    }
                } else {
                    try {
                        Client.makeCollection(target)
                    } catch (e: DavException) {
                        val exception = e.toFileSystemException(target.toString())
                        if (targetFile?.isDirectory != true
                            || exception !is FileAlreadyExistsException) {
                            throw exception
                        }
                    }
                }
                copyOptions.progressListener?.invoke(sourceSize)
            }
            sourceResponse.isSymbolicLink ->
                throw UnsupportedOperationException("Cannot copy symbolic links")
            else -> {
                val writePath = if (targetFile != null) target.tempCopy() else target
                val sourceInputStream = try {
                    Client.get(source)
                } catch (e: DavException) {
                    throw e.toFileSystemException(source.toString())
                }
                try {
                    val targetOutputStream = try {
                        Client.put(writePath)
                    } catch (e: DavException) {
                        throw e.toFileSystemException(writePath.toString())
                    }
                    var successful = false
                    try {
                        sourceInputStream.copyTo(
                            targetOutputStream, copyOptions.progressIntervalMillis,
                            copyOptions.progressListener
                        )
                        if (writePath != target) {
                            try {
                                Client.move(writePath, target, overwrite = true)
                            } catch (e: DavException) {
                                throw e.toFileSystemException(target.toString())
                            }
                        }
                        successful = true
                    } finally {
                        try {
                            targetOutputStream.close()
                        } catch (e: DavException) {
                            throw e.toFileSystemException(target.toString())
                        } finally {
                            if (!successful && writePath != target) {
                                try {
                                    Client.delete(writePath)
                                } catch (e: DavException) {
                                    e.printStackTrace()
                                }
                            } else if (!successful) {
                                try {
                                    Client.delete(writePath)
                                } catch (e: DavException) {
                                    e.printStackTrace()
                                }
                            }
                        }
                    }
                } finally {
                    try {
                        sourceInputStream.close()
                    } catch (e: DavException) {
                        throw e.toFileSystemException(source.toString())
                    }
                }
            }
        }
        // We don't take error when copying attribute fatal, so errors will only be logged from now
        // on.
        if (!sourceResponse.isSymbolicLink) {
            val lastModifiedTime = sourceResponse.lastModifiedTime
            if (lastModifiedTime != null) {
                try {
                    Client.setLastModifiedTime(target, lastModifiedTime)
                } catch (e: DavException) {
                    e.printStackTrace()
                }
            }
        }
    }

    @Throws(IOException::class)
    fun move(source: WebDavPath, target: WebDavPath, copyOptions: CopyOptions) {
        val sourceResponse = try {
            Client.findProperties(source, copyOptions.noFollowLinks)
        } catch (e: DavException) {
            throw e.toFileSystemException(source.toString())
        }
        val targetResponse = try {
            Client.findPropertiesOrNull(target, true)
        } catch (e: DavException) {
            throw e.toFileSystemException(target.toString())
        }
        val sourceSize = sourceResponse.size
        if (targetResponse != null) {
            if (source == target) {
                copyOptions.progressListener?.invoke(sourceSize)
                return
            }
            if (!copyOptions.replaceExisting) {
                throw FileAlreadyExistsException(source.toString(), target.toString(), null)
            }
        }
        var renameSuccessful = false
        try {
            Client.move(
                source, target,
                overwrite = targetResponse != null,
                sourceIsDirectory = sourceResponse.isDirectory
            )
            renameSuccessful = true
        } catch (e: DavException) {
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
            Client.delete(source, sourceResponse.isDirectory)
        } catch (e: DavException) {
            if (e.toFileSystemException(source.toString()) !is NoSuchFileException
                && !(sourceResponse.isDirectory && targetResponse?.isDirectory == true)) {
                try {
                    Client.delete(target, sourceResponse.isDirectory)
                } catch (e2: DavException) {
                    e.addSuppressed(e2.toFileSystemException(target.toString()))
                }
            }
            throw e.toFileSystemException(source.toString())
        }
    }

    private fun WebDavPath.tempCopy(): WebDavPath {
        val name = fileNameByteString?.toString().orEmpty()
        return resolveSibling(".$name.mftmp${System.nanoTime()}".toByteString())
    }
}
