/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.nfs

import android.net.Uri
import android.system.OsConstants
import java8.nio.channels.FileChannel
import java8.nio.channels.SeekableByteChannel
import java8.nio.file.AccessMode
import java8.nio.file.CopyOption
import java8.nio.file.DirectoryStream
import java8.nio.file.FileStore
import java8.nio.file.FileSystem
import java8.nio.file.FileSystemAlreadyExistsException
import java8.nio.file.FileSystemNotFoundException
import java8.nio.file.LinkOption
import java8.nio.file.OpenOption
import java8.nio.file.Path
import java8.nio.file.ProviderMismatchException
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileAttribute
import java8.nio.file.attribute.FileAttributeView
import java8.nio.file.spi.FileSystemProvider
import me.zhanghai.android.files.provider.common.ByteStringPath
import me.zhanghai.android.files.provider.common.OpenOptions
import me.zhanghai.android.files.provider.common.PathListDirectoryStream
import me.zhanghai.android.files.provider.common.PathObservable
import me.zhanghai.android.files.provider.common.PathObservableProvider
import me.zhanghai.android.files.provider.common.PosixFileMode
import me.zhanghai.android.files.provider.common.Searchable
import me.zhanghai.android.files.provider.common.WalkFileTreeSearchable
import me.zhanghai.android.files.provider.common.WatchServicePathObservable
import me.zhanghai.android.files.provider.common.decodedPathByteString
import me.zhanghai.android.files.provider.common.decodedQueryByteString
import me.zhanghai.android.files.provider.common.toAccessModes
import me.zhanghai.android.files.provider.common.toByteString
import me.zhanghai.android.files.provider.common.toCopyOptions
import me.zhanghai.android.files.provider.common.toInt
import me.zhanghai.android.files.provider.common.toLinkOptions
import me.zhanghai.android.files.provider.common.toOpenOptions
import me.zhanghai.android.files.provider.nfs.client.Authority
import me.zhanghai.android.files.provider.nfs.client.Client
import me.zhanghai.android.files.provider.nfs.client.ClientException
import java.io.IOException
import java.net.URI

object NfsFileSystemProvider : FileSystemProvider(), PathObservableProvider, Searchable {
    const val scheme = "nfs"

    private val HIDDEN_FILE_NAME_PREFIX = ".".toByteString()

    private val fileSystems = mutableMapOf<Authority, NfsFileSystem>()

    private val lock = Any()

    override fun getScheme(): String = scheme

    override fun newFileSystem(uri: URI, env: Map<String, *>): FileSystem {
        uri.requireSameScheme()
        val authority = uri.nfsAuthority
        synchronized(lock) {
            if (fileSystems[authority] != null) {
                throw FileSystemAlreadyExistsException(authority.toString())
            }
            return newFileSystemLocked(authority)
        }
    }

    internal fun getOrNewFileSystem(authority: Authority): NfsFileSystem =
        synchronized(lock) { fileSystems[authority] ?: newFileSystemLocked(authority) }

    private fun newFileSystemLocked(authority: Authority): NfsFileSystem {
        val fileSystem = NfsFileSystem(this, authority)
        fileSystems[authority] = fileSystem
        return fileSystem
    }

    override fun getFileSystem(uri: URI): FileSystem {
        uri.requireSameScheme()
        val authority = uri.nfsAuthority
        return synchronized(lock) { fileSystems[authority] }
            ?: throw FileSystemNotFoundException(authority.toString())
    }

    internal fun removeFileSystem(fileSystem: NfsFileSystem) {
        val authority = fileSystem.authority
        synchronized(lock) { fileSystems.remove(authority) }
    }

    override fun getPath(uri: URI): Path {
        uri.requireSameScheme()
        val authority = uri.nfsAuthority
        val path = uri.decodedPathByteString
            ?: throw IllegalArgumentException("URI must have a path")
        return getOrNewFileSystem(authority).getPath(path)
    }

    private fun URI.requireSameScheme() {
        require(this.scheme == NfsFileSystemProvider.scheme) {
            "URI scheme ${this.scheme} must be ${NfsFileSystemProvider.scheme}"
        }
    }

    private val URI.nfsAuthority: Authority
        get() {
            val host = Authority.normalizeHost(
                host ?: throw IllegalArgumentException("URI must have a host")
            )
            val nfsPort = if (port != -1) port else Authority.PORTMAPPER
            val query = decodedQueryByteString?.toString()
                ?: throw IllegalArgumentException("URI must have an export")
            val queryUri = Uri.parse("nfs://local/?$query")
            val export = queryUri.getQueryParameter(NfsPath.QUERY_PARAMETER_EXPORT)
                ?: throw IllegalArgumentException("URI must have an export")
            val uid = queryUri.getQueryParameter(NfsPath.QUERY_PARAMETER_UID)?.toIntOrNull()
                ?: Authority.DEFAULT_UID
            val gid = queryUri.getQueryParameter(NfsPath.QUERY_PARAMETER_GID)?.toIntOrNull()
                ?: Authority.DEFAULT_GID
            return Authority(host, nfsPort, Authority.normalizeExport(export), uid, gid)
        }

    @Throws(IOException::class)
    override fun newFileChannel(
        file: Path,
        options: Set<OpenOption>,
        vararg attributes: FileAttribute<*>
    ): FileChannel {
        file as? NfsPath ?: throw ProviderMismatchException(file.toString())
        throw UnsupportedOperationException()
    }

    @Throws(IOException::class)
    override fun newByteChannel(
        file: Path,
        options: Set<OpenOption>,
        vararg attributes: FileAttribute<*>
    ): SeekableByteChannel {
        file as? NfsPath ?: throw ProviderMismatchException(file.toString())
        val openOptions = options.toOpenOptions()
        val flags = openOptions.toNfsFlags()
        val mode = (PosixFileMode.fromAttributes(attributes) ?: PosixFileMode.CREATE_FILE_DEFAULT)
            .toInt()
        return try {
            Client.openByteChannel(file, flags, mode, openOptions.append, openOptions.deleteOnClose)
        } catch (e: ClientException) {
            throw e.toFileSystemException(file.toString())
        }
    }

    @Throws(IOException::class)
    override fun newDirectoryStream(
        directory: Path,
        filter: DirectoryStream.Filter<in Path>
    ): DirectoryStream<Path> {
        directory as? NfsPath ?: throw ProviderMismatchException(directory.toString())
        val paths = try {
            @Suppress("UNCHECKED_CAST")
            Client.list(directory) as List<Path>
        } catch (e: ClientException) {
            throw e.toFileSystemException(directory.toString())
        }
        return PathListDirectoryStream(paths, filter)
    }

    @Throws(IOException::class)
    override fun createDirectory(directory: Path, vararg attributes: FileAttribute<*>) {
        directory as? NfsPath ?: throw ProviderMismatchException(directory.toString())
        val mode = (PosixFileMode.fromAttributes(attributes)
            ?: PosixFileMode.CREATE_DIRECTORY_DEFAULT).toInt()
        try {
            Client.mkdir(directory, mode)
        } catch (e: ClientException) {
            throw e.toFileSystemException(directory.toString())
        }
    }

    override fun createSymbolicLink(link: Path, target: Path, vararg attributes: FileAttribute<*>) {
        link as? NfsPath ?: throw ProviderMismatchException(link.toString())
        throw UnsupportedOperationException()
    }

    override fun createLink(link: Path, existing: Path) {
        link as? NfsPath ?: throw ProviderMismatchException(link.toString())
        existing as? NfsPath ?: throw ProviderMismatchException(existing.toString())
        throw UnsupportedOperationException()
    }

    @Throws(IOException::class)
    override fun delete(path: Path) {
        path as? NfsPath ?: throw ProviderMismatchException(path.toString())
        try {
            Client.delete(path)
        } catch (e: ClientException) {
            throw e.toFileSystemException(path.toString())
        }
    }

    override fun readSymbolicLink(link: Path): Path {
        link as? NfsPath ?: throw ProviderMismatchException(link.toString())
        val target = try {
            Client.readLink(link)
        } catch (e: ClientException) {
            throw e.toFileSystemException(link.toString())
        }
        return ByteStringPath(target)
    }

    @Throws(IOException::class)
    override fun copy(source: Path, target: Path, vararg options: CopyOption) {
        source as? NfsPath ?: throw ProviderMismatchException(source.toString())
        target as? NfsPath ?: throw ProviderMismatchException(target.toString())
        NfsCopyMove.copy(source, target, options.toCopyOptions())
    }

    @Throws(IOException::class)
    override fun move(source: Path, target: Path, vararg options: CopyOption) {
        source as? NfsPath ?: throw ProviderMismatchException(source.toString())
        target as? NfsPath ?: throw ProviderMismatchException(target.toString())
        NfsCopyMove.move(source, target, options.toCopyOptions())
    }

    override fun isSameFile(path: Path, path2: Path): Boolean {
        path as? NfsPath ?: throw ProviderMismatchException(path.toString())
        return path == path2
    }

    override fun isHidden(path: Path): Boolean {
        path as? NfsPath ?: throw ProviderMismatchException(path.toString())
        val fileName = path.fileNameByteString ?: return false
        return fileName.startsWith(HIDDEN_FILE_NAME_PREFIX)
    }

    override fun getFileStore(path: Path): FileStore {
        path as? NfsPath ?: throw ProviderMismatchException(path.toString())
        throw UnsupportedOperationException()
    }

    @Throws(IOException::class)
    override fun checkAccess(path: Path, vararg modes: AccessMode) {
        path as? NfsPath ?: throw ProviderMismatchException(path.toString())
        val accessModes = modes.toAccessModes()
        if (accessModes.execute) {
            throw UnsupportedOperationException(AccessMode.EXECUTE.toString())
        }
        try {
            if (accessModes.read || accessModes.write) {
                Client.access(path, accessModes.read, accessModes.write)
            } else {
                Client.stat(path, false)
            }
        } catch (e: ClientException) {
            throw e.toFileSystemException(path.toString())
        }
    }

    override fun <V : FileAttributeView> getFileAttributeView(
        path: Path,
        type: Class<V>,
        vararg options: LinkOption
    ): V? {
        if (!supportsFileAttributeView(type)) {
            return null
        }
        @Suppress("UNCHECKED_CAST")
        return getFileAttributeView(path, *options) as V
    }

    internal fun supportsFileAttributeView(type: Class<out FileAttributeView>): Boolean =
        type.isAssignableFrom(NfsFileAttributeView::class.java)

    @Throws(IOException::class)
    override fun <A : BasicFileAttributes> readAttributes(
        path: Path,
        type: Class<A>,
        vararg options: LinkOption
    ): A {
        if (!type.isAssignableFrom(NfsFileAttributes::class.java)) {
            throw UnsupportedOperationException(type.toString())
        }
        @Suppress("UNCHECKED_CAST")
        return getFileAttributeView(path, *options).readAttributes() as A
    }

    private fun getFileAttributeView(
        path: Path,
        vararg options: LinkOption
    ): NfsFileAttributeView {
        path as? NfsPath ?: throw ProviderMismatchException(path.toString())
        val linkOptions = options.toLinkOptions()
        return NfsFileAttributeView(path, linkOptions.noFollowLinks)
    }

    override fun readAttributes(
        path: Path,
        attributes: String,
        vararg options: LinkOption
    ): Map<String, Any> {
        path as? NfsPath ?: throw ProviderMismatchException(path.toString())
        throw UnsupportedOperationException()
    }

    override fun setAttribute(
        path: Path,
        attribute: String,
        value: Any,
        vararg options: LinkOption
    ) {
        path as? NfsPath ?: throw ProviderMismatchException(path.toString())
        throw UnsupportedOperationException()
    }

    @Throws(IOException::class)
    override fun observe(path: Path, intervalMillis: Long): PathObservable {
        path as? NfsPath ?: throw ProviderMismatchException(path.toString())
        return WatchServicePathObservable(path, intervalMillis)
    }

    @Throws(IOException::class)
    override fun search(
        directory: Path,
        query: String,
        intervalMillis: Long,
        listener: (List<Path>) -> Unit
    ) {
        directory as? NfsPath ?: throw ProviderMismatchException(directory.toString())
        WalkFileTreeSearchable.search(directory, query, intervalMillis, listener)
    }
}

private fun OpenOptions.toNfsFlags(): Int {
    var flags = when {
        read && write -> OsConstants.O_RDWR
        write -> OsConstants.O_WRONLY
        else -> OsConstants.O_RDONLY
    }
    if (append) {
        flags = flags or OsConstants.O_APPEND
    }
    if (truncateExisting) {
        flags = flags or OsConstants.O_TRUNC
    }
    if (create || createNew) {
        flags = flags or OsConstants.O_CREAT
    }
    if (createNew) {
        flags = flags or OsConstants.O_EXCL
    }
    if (sync || dsync) {
        flags = flags or OsConstants.O_SYNC
    }
    return flags
}
