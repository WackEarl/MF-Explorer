/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.nfs

import android.net.Uri
import android.os.Parcel
import android.os.Parcelable
import java8.nio.file.FileSystem
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.ProviderMismatchException
import java8.nio.file.WatchEvent
import java8.nio.file.WatchKey
import java8.nio.file.WatchService
import me.zhanghai.android.files.provider.common.ByteString
import me.zhanghai.android.files.provider.common.ByteStringListPath
import me.zhanghai.android.files.provider.common.LocalWatchService
import me.zhanghai.android.files.provider.common.MountAbsoluteLink
import me.zhanghai.android.files.provider.common.UriAuthority
import me.zhanghai.android.files.provider.common.toByteString
import me.zhanghai.android.files.provider.nfs.client.Authority
import me.zhanghai.android.files.provider.nfs.client.Client
import me.zhanghai.android.files.provider.nfs.client.NfsStat
import me.zhanghai.android.files.util.readParcelable
import java.io.File
import java.io.IOException

internal class NfsPath : ByteStringListPath<NfsPath>, Client.Path, MountAbsoluteLink {
    private val fileSystem: NfsFileSystem

    constructor(fileSystem: NfsFileSystem, path: ByteString) : super(NfsFileSystem.SEPARATOR, path) {
        this.fileSystem = fileSystem
    }

    private constructor(
        fileSystem: NfsFileSystem,
        absolute: Boolean,
        segments: List<ByteString>
    ) : super(NfsFileSystem.SEPARATOR, absolute, segments) {
        this.fileSystem = fileSystem
    }

    override fun isPathAbsolute(path: ByteString): Boolean =
        path.isNotEmpty() && path[0] == NfsFileSystem.SEPARATOR

    override fun createPath(path: ByteString): NfsPath = NfsPath(fileSystem, path)

    override fun createPath(absolute: Boolean, segments: List<ByteString>): NfsPath =
        NfsPath(fileSystem, absolute, segments)

    override val uriScheme: String
        get() = NfsFileSystemProvider.scheme

    override val uriAuthority: UriAuthority
        get() = fileSystem.authority.toUriAuthority()

    override val uriQuery: ByteString?
        get() {
            val authority = fileSystem.authority
            val query = Uri.Builder().apply {
                appendQueryParameter(QUERY_PARAMETER_EXPORT, authority.export)
                if (authority.uid != Authority.DEFAULT_UID) {
                    appendQueryParameter(QUERY_PARAMETER_UID, authority.uid.toString())
                }
                if (authority.gid != Authority.DEFAULT_GID) {
                    appendQueryParameter(QUERY_PARAMETER_GID, authority.gid.toString())
                }
            }.build().query
            return query?.toByteString()
        }

    override val defaultDirectory: NfsPath
        get() = fileSystem.defaultDirectory

    override fun getFileSystem(): FileSystem = fileSystem

    override fun getRoot(): NfsPath? = if (isAbsolute) fileSystem.rootDirectory else null

    @Throws(IOException::class)
    override fun toRealPath(vararg options: LinkOption): NfsPath {
        throw UnsupportedOperationException()
    }

    override fun toFile(): File {
        throw UnsupportedOperationException()
    }

    @Throws(IOException::class)
    override fun register(
        watcher: WatchService,
        events: Array<WatchEvent.Kind<*>>,
        vararg modifiers: WatchEvent.Modifier
    ): WatchKey {
        if (watcher !is LocalWatchService) {
            throw ProviderMismatchException(watcher.toString())
        }
        return watcher.register(this, events, *modifiers)
    }

    override val authority: Authority
        get() = fileSystem.authority

    override fun mountAbsoluteLink(target: ByteString): NfsPath? {
        if (target.isEmpty() || target[0] != NfsFileSystem.SEPARATOR) {
            return null
        }
        val export = authority.export
        if (export.isEmpty() || export == "/") {
            return null
        }
        val prefix = export.toByteString()
        if (!target.startsWith(prefix)) {
            return null
        }
        if (target.length == prefix.length) {
            return fileSystem.rootDirectory
        }
        if (target[prefix.length] != NfsFileSystem.SEPARATOR) {
            return null
        }
        return createPath(target.substring(prefix.length)).normalize()
    }

    override val remoteBytes: ByteArray
        get() = toByteString().toBytes()

    /** Attributes from the directory listing. Consumed by the first no-follow stat. */
    @Volatile
    private var listedStat: NfsStat? = null

    internal fun cacheListedStat(stat: NfsStat) {
        listedStat = stat
    }

    internal fun takeListedStat(): NfsStat? {
        val stat = listedStat ?: return null
        listedStat = null
        return stat
    }

    private constructor(source: Parcel) : super(source) {
        fileSystem = source.readParcelable()!!
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        super.writeToParcel(dest, flags)
        dest.writeParcelable(fileSystem, flags)
    }

    companion object {
        const val QUERY_PARAMETER_EXPORT = "export"
        const val QUERY_PARAMETER_UID = "uid"
        const val QUERY_PARAMETER_GID = "gid"

        @JvmField
        val CREATOR = object : Parcelable.Creator<NfsPath> {
            override fun createFromParcel(source: Parcel): NfsPath = NfsPath(source)

            override fun newArray(size: Int): Array<NfsPath?> = arrayOfNulls(size)
        }
    }
}
