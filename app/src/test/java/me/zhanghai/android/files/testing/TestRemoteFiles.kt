/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.testing

import java8.nio.channels.SeekableByteChannel
import java8.nio.file.AccessMode
import java8.nio.file.CopyOption
import java8.nio.file.DirectoryStream
import java8.nio.file.FileStore
import java8.nio.file.FileSystem
import java8.nio.file.LinkOption
import java8.nio.file.OpenOption
import java8.nio.file.Path
import java8.nio.file.PathMatcher
import java8.nio.file.WatchService
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileAttribute
import java8.nio.file.attribute.FileAttributeView
import java8.nio.file.attribute.FileTime
import java8.nio.file.attribute.UserPrincipalLookupService
import java8.nio.file.spi.FileSystemProvider
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.lang.reflect.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Files on a remote file system that only supports opening an input stream, counting every byte
 * read from them. Paths of it are neither Linux nor document paths, so they count as remote.
 */
class TestRemoteFiles {
    private val files = mutableMapOf<String, ByteArray>()

    val bytesRead = AtomicLong()
    val opens = AtomicInteger()

    fun add(name: String, bytes: ByteArray): Pair<Path, BasicFileAttributes> {
        files[name] = bytes
        return path(name) to attributes(bytes.size.toLong())
    }

    fun reset() {
        bytesRead.set(0)
        opens.set(0)
    }

    private fun path(name: String): Path =
        Proxy.newProxyInstance(Path::class.java.classLoader, arrayOf(Path::class.java)) {
                proxy, method, args ->
            when (method.name) {
                "toString" -> "/remote/$name"
                "hashCode" -> name.hashCode()
                "equals" -> proxy === args?.get(0)
                "getFileSystem" -> fileSystem
                else -> throw UnsupportedOperationException("Path.${method.name}")
            }
        } as Path

    private fun nameOf(path: Path): String = path.toString().removePrefix("/remote/")

    private fun attributes(size: Long): BasicFileAttributes =
        Proxy.newProxyInstance(
            BasicFileAttributes::class.java.classLoader, arrayOf(BasicFileAttributes::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "lastModifiedTime" -> FileTime.from(1_700_000_000_000, TimeUnit.MILLISECONDS)
                "size" -> size
                "isRegularFile" -> true
                "isDirectory", "isSymbolicLink", "isOther" -> false
                "toString" -> "attributes($size)"
                "hashCode" -> size.hashCode()
                "equals" -> proxy === args?.get(0)
                else -> throw UnsupportedOperationException("Attributes.${method.name}")
            }
        } as BasicFileAttributes

    private inner class CountingInputStream(inputStream: InputStream) :
        FilterInputStream(inputStream) {
        override fun read(): Int = super.read().also { if (it >= 0) bytesRead.incrementAndGet() }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) bytesRead.addAndGet(it.toLong()) }

        override fun skip(n: Long): Long =
            // A skip on a remote file reads the skipped bytes too.
            super.skip(n).also { bytesRead.addAndGet(it) }
    }

    private val provider = object : FileSystemProvider() {
        override fun newInputStream(path: Path, vararg options: OpenOption): InputStream {
            opens.incrementAndGet()
            val bytes = files[nameOf(path)] ?: throw java8.nio.file.NoSuchFileException(
                path.toString()
            )
            return CountingInputStream(ByteArrayInputStream(bytes))
        }

        override fun getScheme(): String = "test-remote"
        override fun newFileSystem(uri: URI, env: Map<String, *>): FileSystem = unsupported()
        override fun getFileSystem(uri: URI): FileSystem = fileSystem
        override fun getPath(uri: URI): Path = unsupported()
        override fun newByteChannel(
            path: Path,
            options: Set<OpenOption>,
            vararg attributes: FileAttribute<*>
        ): SeekableByteChannel = unsupported()
        override fun newDirectoryStream(
            directory: Path,
            filter: DirectoryStream.Filter<in Path>
        ): DirectoryStream<Path> = unsupported()
        override fun createDirectory(directory: Path, vararg attributes: FileAttribute<*>) =
            unsupported()
        override fun delete(path: Path) = unsupported()
        override fun copy(source: Path, target: Path, vararg options: CopyOption) = unsupported()
        override fun move(source: Path, target: Path, vararg options: CopyOption) = unsupported()
        override fun isSameFile(path: Path, path2: Path): Boolean = path == path2
        override fun isHidden(path: Path): Boolean = false
        override fun getFileStore(path: Path): FileStore = unsupported()
        override fun checkAccess(path: Path, vararg modes: AccessMode) = unsupported()
        override fun <V : FileAttributeView> getFileAttributeView(
            path: Path,
            type: Class<V>,
            vararg options: LinkOption
        ): V = unsupported()
        override fun <A : BasicFileAttributes> readAttributes(
            path: Path,
            type: Class<A>,
            vararg options: LinkOption
        ): A = unsupported()
        override fun readAttributes(
            path: Path,
            attributes: String,
            vararg options: LinkOption
        ): Map<String, Any> = unsupported()
        override fun setAttribute(
            path: Path,
            attribute: String,
            value: Any,
            vararg options: LinkOption
        ) = unsupported()
    }

    private val fileSystem: FileSystem = object : FileSystem() {
        override fun provider(): FileSystemProvider = provider
        override fun close() {}
        override fun isOpen(): Boolean = true
        override fun isReadOnly(): Boolean = true
        override fun getSeparator(): String = "/"
        override fun getRootDirectories(): Iterable<Path> = unsupported()
        override fun getFileStores(): Iterable<FileStore> = unsupported()
        override fun supportedFileAttributeViews(): Set<String> = setOf("basic")
        override fun getPath(first: String, vararg more: String): Path = unsupported()
        override fun getPathMatcher(syntaxAndPattern: String): PathMatcher = unsupported()
        override fun getUserPrincipalLookupService(): UserPrincipalLookupService = unsupported()
        override fun newWatchService(): WatchService = unsupported()
    }

    private fun unsupported(): Nothing = throw UnsupportedOperationException()
}
