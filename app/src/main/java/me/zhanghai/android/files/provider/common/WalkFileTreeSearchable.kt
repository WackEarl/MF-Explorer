/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.common

import java8.nio.file.FileVisitResult
import java8.nio.file.FileVisitor
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import java.io.IOException
import java.io.InterruptedIOException
import java.util.ArrayDeque

object WalkFileTreeSearchable {
    @Throws(IOException::class)
    fun search(
        directory: Path,
        query: String,
        intervalMillis: Long,
        listener: (List<Path>) -> Unit
    ) {
        val paths = mutableListOf<Path>()
        // We cannot use Files.find() or Files.walk() because it cannot ignore exceptions.
        walkFileTreeForSearch(directory, object : FileVisitor<Path> {
            private var lastProgressMillis = 0L

            @Throws(InterruptedIOException::class)
            override fun preVisitDirectory(
                directory: Path,
                attributes: BasicFileAttributes
            ): FileVisitResult {
                visit(directory)
                throwIfInterrupted()
                return FileVisitResult.CONTINUE
            }

            @Throws(InterruptedIOException::class)
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                visit(file)
                throwIfInterrupted()
                return FileVisitResult.CONTINUE
            }

            @Throws(InterruptedIOException::class)
            override fun visitFileFailed(file: Path, exception: IOException): FileVisitResult {
                if (exception is InterruptedIOException) {
                    throw exception
                }
                exception.printStackTrace()
                visit(file)
                throwIfInterrupted()
                return FileVisitResult.CONTINUE
            }

            @Throws(InterruptedIOException::class)
            override fun postVisitDirectory(
                directory: Path,
                exception: IOException?
            ): FileVisitResult {
                if (exception is InterruptedIOException) {
                    throw exception
                }
                exception?.printStackTrace()
                throwIfInterrupted()
                return FileVisitResult.CONTINUE
            }

            private val reported = HashSet<String>()

            private fun visit(path: Path) {
                // Exclude the directory being searched.
                if (path == directory || !reported.add(path.toString())) {
                    return
                }
                val fileName = path.fileName
                if (fileName != null && fileName.toString().contains(query, true)) {
                    paths.add(path)
                }
                if (paths.isNotEmpty()) {
                    val currentTimeMillis = System.currentTimeMillis()
                    if (currentTimeMillis >= lastProgressMillis + intervalMillis) {
                        listener(paths)
                        lastProgressMillis = currentTimeMillis
                        paths.clear()
                    }
                }
            }
        })
        if (paths.isNotEmpty()) {
            listener(paths)
        }
    }

    // Breadth-first. A symlink under [start] is followed even when the target
    // lies outside [start]. That target is then its own root: real directories
    // under it are searched, and a further symlink may not leave it. A link to
    // the export root therefore cannot walk into a second tree, and the same
    // directory is never listed twice.
    @Throws(IOException::class)
    private fun walkFileTreeForSearch(start: Path, visitor: FileVisitor<in Path>): Path {
        val pending = ArrayDeque<WalkDirectory>()
        pending.add(WalkDirectory(start, start))
        val seen = HashSet<String>()
        while (pending.isNotEmpty()) {
            throwIfInterrupted()
            val item = pending.removeFirst()
            val directory = item.path
            if (directory != item.root && !directory.startsWith(item.root)) {
                continue
            }
            if (!seen.add(directory.toString())) {
                continue
            }
            val children = try {
                directory.newDirectoryStream().use { it.toList() }
            } catch (e: IOException) {
                visitor.visitFileFailed(directory, e)
                continue
            }
            for (child in children) {
                throwIfInterrupted()
                val childName = child.fileName?.toString()
                if (childName == "." || childName == "..") {
                    continue
                }
                if (!child.startsWith(item.root)) {
                    continue
                }
                val attributes = try {
                    child.readAttributes(
                        BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS
                    )
                } catch (e: IOException) {
                    visitor.visitFileFailed(child, e)
                    continue
                }
                visitor.visitFile(child, attributes)
                when {
                    attributes.isDirectory -> pending.add(WalkDirectory(child, item.root))
                    attributes.isSymbolicLink -> {
                        val target = child.followLink() ?: continue
                        val root = linkRoot(item.root, target.path, start) ?: continue
                        if (target.path != start) {
                            visitor.visitFile(target.path, attributes)
                        }
                        if (target.isDirectory) {
                            pending.add(WalkDirectory(target.path, root))
                        }
                    }
                }
            }
        }
        return start
    }

    // [current] is the root the link was found in. A link inside [start] may
    // name a new root. A link that is already outside [start] may not.
    private fun linkRoot(current: Path, target: Path, start: Path): Path? = when {
        target.startsWith(start) -> start
        target.startsWith(current) -> current
        current == start -> target
        else -> null
    }

    // The link itself is already visited. The returned path is the first real
    // file or directory on the chain.
    private fun Path.followLink(): FollowedLink? {
        var current = this
        val hops = HashSet<String>()
        repeat(MAX_SYMBOLIC_LINKS) {
            throwIfInterrupted()
            if (!hops.add(current.toString())) {
                return null
            }
            val next = current.resolveSymbolicLink() ?: return null
            val attributes = try {
                next.readAttributes(
                    BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS
                )
            } catch (e: IOException) {
                return null
            } catch (e: RuntimeException) {
                return null
            }
            if (!attributes.isSymbolicLink) {
                return FollowedLink(next, attributes.isDirectory)
            }
            current = next
        }
        return null
    }

    private fun Path.resolveSymbolicLink(): Path? {
        val raw = try {
            readSymbolicLinkByteString()
        } catch (e: IOException) {
            return null
        } catch (e: RuntimeException) {
            return null
        }
        val base = parent?.asByteStringListPath() ?: return null
        val resolved = try {
            val mapped = (this as? MountAbsoluteLink)?.mountAbsoluteLink(raw)
            (mapped ?: base.resolve(raw)).normalize()
        } catch (e: RuntimeException) {
            return null
        }
        return resolved
    }

    @Throws(InterruptedIOException::class)
    private fun throwIfInterrupted() {
        if (Thread.interrupted()) {
            throw InterruptedIOException()
        }
    }

    // libnfs stops at the same count while following a link.
    private const val MAX_SYMBOLIC_LINKS = 40

    private class WalkDirectory(val path: Path, val root: Path)

    private class FollowedLink(val path: Path, val isDirectory: Boolean)
}

/**
 * Absolute symlink text is a server path. [mountAbsoluteLink] turns one that
 * starts with the export into a path on this mount, which is what lookup does
 * when the link is opened. Null keeps the text unchanged.
 */
interface MountAbsoluteLink {
    fun mountAbsoluteLink(target: ByteString): Path?
}
