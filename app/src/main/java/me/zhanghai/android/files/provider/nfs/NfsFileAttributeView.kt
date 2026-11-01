/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.nfs

import java8.nio.file.LinkOption
import java8.nio.file.attribute.FileTime
import me.zhanghai.android.files.provider.common.ByteString
import me.zhanghai.android.files.provider.common.PosixFileAttributeView
import me.zhanghai.android.files.provider.common.PosixFileModeBit
import me.zhanghai.android.files.provider.common.PosixGroup
import me.zhanghai.android.files.provider.common.PosixUser
import me.zhanghai.android.files.provider.common.toInt
import me.zhanghai.android.files.provider.nfs.client.Client
import me.zhanghai.android.files.provider.nfs.client.ClientException
import java.io.IOException

internal class NfsFileAttributeView(
    private val path: NfsPath,
    private val noFollowLinks: Boolean
) : PosixFileAttributeView {
    override fun name(): String = NAME

    @Throws(IOException::class)
    override fun readAttributes(): NfsFileAttributes = NfsFileAttributes.from(getStat(), path)

    override fun setTimes(
        lastModifiedTime: FileTime?,
        lastAccessTime: FileTime?,
        createTime: FileTime?
    ) {
        if (lastAccessTime == null && lastModifiedTime == null) {
            if (createTime != null) {
                throw UnsupportedOperationException("createTime")
            }
            return
        }
        if (noFollowLinks) {
            throw UnsupportedOperationException(LinkOption.NOFOLLOW_LINKS.toString())
        }
        val current = if (lastAccessTime == null || lastModifiedTime == null) getStat() else null
        val atime = lastAccessTime?.toInstant()
        val mtime = lastModifiedTime?.toInstant()
        try {
            Client.utimes(
                path,
                atime?.epochSecond ?: current!!.atimeSec,
                atime?.nano ?: current!!.atimeNsec,
                mtime?.epochSecond ?: current!!.mtimeSec,
                mtime?.nano ?: current!!.mtimeNsec
            )
        } catch (e: ClientException) {
            throw e.toFileSystemException(path.toString())
        }
    }

    @Throws(IOException::class)
    override fun setOwner(owner: PosixUser) {
        if (noFollowLinks) {
            throw UnsupportedOperationException(LinkOption.NOFOLLOW_LINKS.toString())
        }
        val current = getStat()
        try {
            Client.chown(path, owner.id, current.gid)
        } catch (e: ClientException) {
            throw e.toFileSystemException(path.toString())
        }
    }

    @Throws(IOException::class)
    override fun setGroup(group: PosixGroup) {
        if (noFollowLinks) {
            throw UnsupportedOperationException(LinkOption.NOFOLLOW_LINKS.toString())
        }
        val current = getStat()
        try {
            Client.chown(path, current.uid, group.id)
        } catch (e: ClientException) {
            throw e.toFileSystemException(path.toString())
        }
    }

    @Throws(IOException::class)
    override fun setMode(mode: Set<PosixFileModeBit>) {
        if (noFollowLinks) {
            throw UnsupportedOperationException(LinkOption.NOFOLLOW_LINKS.toString())
        }
        try {
            Client.chmod(path, mode.toInt())
        } catch (e: ClientException) {
            throw e.toFileSystemException(path.toString())
        }
    }

    @Throws(IOException::class)
    override fun setSeLinuxContext(context: ByteString) {
        throw UnsupportedOperationException()
    }

    @Throws(IOException::class)
    override fun restoreSeLinuxContext() {
        throw UnsupportedOperationException()
    }

    @Throws(IOException::class)
    private fun getStat() =
        try {
            Client.stat(path, noFollowLinks)
        } catch (e: ClientException) {
            throw e.toFileSystemException(path.toString())
        }

    companion object {
        private val NAME = NfsFileSystemProvider.scheme

        val SUPPORTED_NAMES = setOf("basic", "posix", NAME)
    }
}
