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

            private fun visit(path: Path) {
                // Exclude the directory being searched.
                if (path == directory) {
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

    // Breadth-first, and only real directories under [start]. A symlink is not
    // followed: on NFS it often points at the export root.
    @Throws(IOException::class)
    private fun walkFileTreeForSearch(start: Path, visitor: FileVisitor<in Path>): Path {
        val pending = ArrayDeque<Path>()
        pending.add(start)
        val seen = HashSet<String>()
        while (pending.isNotEmpty()) {
            throwIfInterrupted()
            val directory = pending.removeFirst()
            if (!seen.add(directory.toString())) {
                continue
            }
            if (directory != start && !directory.startsWith(start)) {
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
                if (attributes.isDirectory) {
                    pending.add(child)
                }
            }
        }
        return start
    }

    @Throws(InterruptedIOException::class)
    private fun throwIfInterrupted() {
        if (Thread.interrupted()) {
            throw InterruptedIOException()
        }
    }
}
