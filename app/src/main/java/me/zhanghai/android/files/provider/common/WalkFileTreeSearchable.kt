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

    // Breadth-first. A symlink is followed only when the end of its chain stays
    // inside [start]. A link that leaves [start], including one to the export
    // root or to a parent, is skipped, so search never covers more than the
    // folder being searched. The walk uses real target paths, and each
    // directory is listed once, so a link back up the tree cannot loop.
    @Throws(IOException::class)
    private fun walkFileTreeForSearch(start: Path, visitor: FileVisitor<in Path>): Path {
        val pending = ArrayDeque<Path>()
        pending.add(start)
        val seen = HashSet<String>()
        while (pending.isNotEmpty()) {
            throwIfInterrupted()
            val directory = pending.removeFirst()
            if (!directory.startsWith(start)) {
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
                if (!child.startsWith(start)) {
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
                    attributes.isDirectory -> pending.add(child)
                    attributes.isSymbolicLink -> {
                        val target = child.followLink() ?: continue
                        // Skip a link that leaves the folder being searched.
                        if (!target.path.startsWith(start)) {
                            continue
                        }
                        if (target.path != start) {
                            visitor.visitFile(target.path, attributes)
                        }
                        if (target.isDirectory) {
                            pending.add(target.path)
                        }
                    }
                }
            }
        }
        return start
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

    // A chain of links stops after a few hops. Each hop is a readlink and a
    // stat on the server.
    private const val MAX_SYMBOLIC_LINKS = 8

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
