/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java8.nio.channels.SeekableByteChannel
import java8.nio.file.AccessDeniedException
import java8.nio.file.AccessMode
import java8.nio.file.CopyOption
import java8.nio.file.DirectoryStream
import java8.nio.file.FileStore
import java8.nio.file.FileSystem
import java8.nio.file.LinkOption
import java8.nio.file.NoSuchFileException
import java8.nio.file.NotLinkException
import java8.nio.file.OpenOption
import java8.nio.file.Path
import java8.nio.file.PathMatcher
import java8.nio.file.WatchEvent
import java8.nio.file.WatchKey
import java8.nio.file.WatchService
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileAttribute
import java8.nio.file.attribute.FileAttributeView
import java8.nio.file.attribute.FileTime
import java8.nio.file.attribute.UserPrincipalLookupService
import java8.nio.file.spi.FileSystemProvider
import java.io.File
import java.net.URI

/**
 * A read-only file tree in memory for tests: directories, files and symbolic links, listed and
 * stat'ed through the same java8.nio calls the app uses. [mountMapper] stands in for a mount
 * that maps absolute link text to a path on the mount, as NFS does.
 */
internal class InMemoryFileSystem : FileSystem() {
    private sealed class Node {
        object Directory : Node()
        object RegularFile : Node()
        class SymbolicLink(val target: String) : Node()
    }

    private val nodes = linkedMapOf<String, Node>("/" to Node.Directory)
    private val provider = InMemoryFileSystemProvider(this)

    /** Directories in the order they were listed. */
    val listedDirectories = mutableListOf<String>()

    /** Directories whose listing fails, like a folder without read permission. */
    val unreadableDirectories = mutableSetOf<String>()

    var mountMapper: ((ByteString) -> Path?)? = null

    fun directory(path: String): InMemoryFileSystem = add(path, Node.Directory)

    fun file(path: String): InMemoryFileSystem = add(path, Node.RegularFile)

    fun symbolicLink(path: String, target: String): InMemoryFileSystem =
        add(path, Node.SymbolicLink(target))

    private fun add(path: String, node: Node): InMemoryFileSystem {
        val parent = path.substringBeforeLast('/').ifEmpty { "/" }
        if (parent != path && nodes[parent] == null) {
            directory(parent)
        }
        nodes[path] = node
        return this
    }

    fun path(path: String): InMemoryPath = InMemoryPath(this, path.toByteString())

    fun list(directory: Path): List<Path> {
        val key = directory.toString()
        if (nodes[key] != Node.Directory) {
            throw NoSuchFileException(key)
        }
        if (key in unreadableDirectories) {
            throw AccessDeniedException(key)
        }
        listedDirectories += key
        val prefix = if (key == "/") "/" else "$key/"
        return nodes.keys
            .filter { it != key && it.startsWith(prefix) && '/' !in it.substring(prefix.length) }
            .map { path(it) }
    }

    fun readAttributes(path: Path): BasicFileAttributes {
        val key = path.toString()
        val node = nodes[key] ?: throw NoSuchFileException(key)
        return Attributes(node)
    }

    fun readSymbolicLink(path: Path): Path {
        val key = path.toString()
        val node = nodes[key] ?: throw NoSuchFileException(key)
        node as? Node.SymbolicLink ?: throw NotLinkException(key)
        return ByteStringPath(node.target.toByteString())
    }

    private class Attributes(private val node: Node) : BasicFileAttributes {
        override fun lastModifiedTime(): FileTime = FileTime.fromMillis(0)
        override fun lastAccessTime(): FileTime = FileTime.fromMillis(0)
        override fun creationTime(): FileTime = FileTime.fromMillis(0)
        override fun isRegularFile(): Boolean = node == Node.RegularFile
        override fun isDirectory(): Boolean = node == Node.Directory
        override fun isSymbolicLink(): Boolean = node is Node.SymbolicLink
        override fun isOther(): Boolean = false
        override fun size(): Long = 0
        override fun fileKey(): Any? = null
    }

    override fun provider(): FileSystemProvider = provider
    override fun close() {}
    override fun isOpen(): Boolean = true
    override fun isReadOnly(): Boolean = true
    override fun getSeparator(): String = "/"
    override fun getRootDirectories(): Iterable<Path> = listOf(path("/"))
    override fun getFileStores(): Iterable<FileStore> = throw UnsupportedOperationException()
    override fun supportedFileAttributeViews(): Set<String> = setOf("basic")
    override fun getPath(first: String, vararg more: String): Path =
        path((listOf(first) + more).joinToString("/"))
    override fun getPathMatcher(syntaxAndPattern: String): PathMatcher =
        throw UnsupportedOperationException()
    override fun getUserPrincipalLookupService(): UserPrincipalLookupService =
        throw UnsupportedOperationException()
    override fun newWatchService(): WatchService = throw UnsupportedOperationException()
}

internal class InMemoryPath : ByteStringListPath<InMemoryPath>, MountAbsoluteLink {
    private val fileSystem: InMemoryFileSystem

    constructor(fileSystem: InMemoryFileSystem, path: ByteString) : super(SEPARATOR, path) {
        this.fileSystem = fileSystem
    }

    private constructor(
        fileSystem: InMemoryFileSystem,
        absolute: Boolean,
        segments: List<ByteString>
    ) : super(SEPARATOR, absolute, segments) {
        this.fileSystem = fileSystem
    }

    override fun isPathAbsolute(path: ByteString): Boolean =
        path.isNotEmpty() && path[0] == SEPARATOR

    override fun createPath(path: ByteString): InMemoryPath = InMemoryPath(fileSystem, path)

    override fun createPath(absolute: Boolean, segments: List<ByteString>): InMemoryPath =
        InMemoryPath(fileSystem, absolute, segments)

    override val defaultDirectory: InMemoryPath
        get() = fileSystem.path("/")

    override fun getFileSystem(): FileSystem = fileSystem

    override fun getRoot(): InMemoryPath? = if (isAbsolute) fileSystem.path("/") else null

    override fun toRealPath(vararg options: LinkOption): InMemoryPath =
        throw UnsupportedOperationException()

    override fun toFile(): File = throw UnsupportedOperationException()

    override fun register(
        watcher: WatchService,
        events: Array<WatchEvent.Kind<*>>,
        vararg modifiers: WatchEvent.Modifier
    ): WatchKey = throw UnsupportedOperationException()

    override fun mountAbsoluteLink(target: ByteString): Path? =
        fileSystem.mountMapper?.invoke(target)

    companion object {
        private const val SEPARATOR = '/'.code.toByte()
    }
}

private class InMemoryFileSystemProvider(
    private val fileSystem: InMemoryFileSystem
) : FileSystemProvider() {
    override fun getScheme(): String = "memory"

    override fun newFileSystem(uri: URI, env: Map<String, *>): FileSystem =
        throw UnsupportedOperationException()

    override fun getFileSystem(uri: URI): FileSystem = fileSystem

    override fun getPath(uri: URI): Path = fileSystem.path(uri.path)

    override fun newByteChannel(
        file: Path,
        options: Set<OpenOption>,
        vararg attributes: FileAttribute<*>
    ): SeekableByteChannel = throw UnsupportedOperationException()

    override fun newDirectoryStream(
        directory: Path,
        filter: DirectoryStream.Filter<in Path>
    ): DirectoryStream<Path> = PathListDirectoryStream(fileSystem.list(directory), filter)

    override fun createDirectory(directory: Path, vararg attributes: FileAttribute<*>) =
        throw UnsupportedOperationException()

    override fun delete(path: Path) = throw UnsupportedOperationException()

    override fun readSymbolicLink(link: Path): Path = fileSystem.readSymbolicLink(link)

    override fun copy(source: Path, target: Path, vararg options: CopyOption) =
        throw UnsupportedOperationException()

    override fun move(source: Path, target: Path, vararg options: CopyOption) =
        throw UnsupportedOperationException()

    override fun isSameFile(path: Path, path2: Path): Boolean = path == path2

    override fun isHidden(path: Path): Boolean = false

    override fun getFileStore(path: Path): FileStore = throw UnsupportedOperationException()

    override fun checkAccess(path: Path, vararg modes: AccessMode) {
        fileSystem.readAttributes(path)
    }

    override fun <V : FileAttributeView> getFileAttributeView(
        path: Path,
        type: Class<V>,
        vararg options: LinkOption
    ): V? = null

    override fun <A : BasicFileAttributes> readAttributes(
        path: Path,
        type: Class<A>,
        vararg options: LinkOption
    ): A {
        // Search only ever stats with NOFOLLOW_LINKS, so links are not resolved here.
        require(LinkOption.NOFOLLOW_LINKS in options) { "Only no-follow stats are faked" }
        @Suppress("UNCHECKED_CAST")
        return fileSystem.readAttributes(path) as A
    }

    override fun readAttributes(
        path: Path,
        attributes: String,
        vararg options: LinkOption
    ): Map<String, Any> = throw UnsupportedOperationException()

    override fun setAttribute(
        path: Path,
        attribute: String,
        value: Any,
        vararg options: LinkOption
    ) = throw UnsupportedOperationException()
}
