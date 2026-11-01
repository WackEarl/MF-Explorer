/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.nfs.client

import android.os.Looper
import android.system.OsConstants
import java8.nio.channels.SeekableByteChannel
import me.zhanghai.android.files.provider.common.ByteString
import me.zhanghai.android.files.provider.common.LocalWatchService
import me.zhanghai.android.files.provider.nfs.NfsPath
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock

/**
 * One mounted libnfs context per authority. Contexts are not thread-safe, and a file handle can
 * only be used on the context that opened it, so every call takes that mount's lock instead of
 * handing out a second context. A pool would deadlock a same-thread copy that has a read channel
 * and a write channel open together.
 */
internal object Client {
    private const val TIMEOUT_MILLIS = 30_000

    private val mounts = mutableMapOf<Authority, Mount>()
    private val pins = mutableMapOf<Authority, Int>()

    private class Mount(
        val authority: Authority,
        var context: Long,
        val lock: ReentrantLock = ReentrantLock(),
        var files: Int = 0,
        var alive: Boolean = true
    )

    interface Path {
        val authority: Authority
        val remoteBytes: ByteArray
    }

    /** Keeps the mounted context alive while a file system for this authority is open. */
    fun pin(authority: Authority) {
        synchronized(mounts) {
            pins[authority] = (pins[authority] ?: 0) + 1
        }
    }

    /** Drops a file-system pin and frees the context when nothing is using it. */
    fun unpin(authority: Authority) {
        val mount = synchronized(mounts) {
            val left = (pins[authority] ?: 0) - 1
            if (left <= 0) {
                pins.remove(authority)
            } else {
                pins[authority] = left
            }
            mounts[authority]
        }
        if (mount != null) {
            reap(mount)
        }
    }

    private fun checkThread() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "NFS I/O on the main thread" }
    }

    private fun mount(authority: Authority): Mount {
        synchronized(mounts) {
            mounts[authority]?.let { return it }
        }
        val context = NfsNative.create(authority.uid, authority.gid, TIMEOUT_MILLIS)
        if (context == 0L) {
            throw error(0L, authority.host)
        }
        val host = authority.host.let {
            if (it.startsWith('[') && it.endsWith(']')) it.substring(1, it.length - 1) else it
        }
        // One UI port covers both protocols. Zero leaves discovery to the portmapper.
        val port = authority.nfsPort
        val rc = NfsNative.mount(
            context, host, authority.export.toByteArray(), port, port
        )
        if (rc != 0) {
            val exception = error(context, authority.export)
            NfsNative.destroy(context)
            throw exception
        }
        return synchronized(mounts) {
            mounts[authority]?.let { existing ->
                NfsNative.destroy(context)
                existing
            } ?: Mount(authority, context).also { mounts[authority] = it }
        }
    }

    private inline fun <R> use(authority: Authority, block: (Mount) -> R): R {
        checkThread()
        while (true) {
            val mount = mount(authority)
            mount.lock.lock()
            try {
                if (mount.alive) {
                    return block(mount)
                }
            } finally {
                mount.lock.unlock()
                reap(mount)
            }
        }
    }

    /**
     * Frees a context that no file system still pins and that has no open files.
     * Lock order is the mount lock, then [mounts]. Callers must not hold [mounts].
     */
    private fun reap(mount: Mount) {
        mount.lock.lock()
        try {
            if (!mount.alive) {
                return
            }
            val drop = synchronized(mounts) {
                mount.files == 0 && (pins[mount.authority] ?: 0) == 0 &&
                    mounts[mount.authority] === mount
            }
            if (!drop) {
                return
            }
            mount.alive = false
            synchronized(mounts) {
                if (mounts[mount.authority] === mount) {
                    mounts.remove(mount.authority)
                }
            }
            if (mount.context != 0L) {
                NfsNative.destroy(mount.context)
                mount.context = 0L
            }
        } finally {
            mount.lock.unlock()
        }
    }

    private fun exception(errno: Int, message: String): ClientException {
        val err = if (errno != 0) errno else OsConstants.EIO
        return ClientException(err, message.takeIf { it.isNotEmpty() } ?: "NFS error $err")
    }

    private fun error(context: Long, file: String): ClientException {
        val errno = NfsNative.takeErrno()
        val message = if (context != 0L) NfsNative.getError(context) else "nfs_init_context failed"
        return exception(errno, message)
    }

    private fun check(context: Long, rc: Int, file: String) {
        if (rc < 0) {
            throw error(context, file)
        }
    }

    fun list(directory: NfsPath): List<NfsPath> =
        use(directory.authority) { mount ->
            val names = ArrayList<ByteArray>()
            check(
                mount.context,
                NfsNative.list(mount.context, directory.remoteBytes, names),
                directory.toString()
            )
            names.map { directory.resolve(ByteString(it)) }
        }

    fun stat(path: Path, noFollowLinks: Boolean): NfsStat =
        use(path.authority) { mount ->
            val values = NfsNative.stat(mount.context, path.remoteBytes, noFollowLinks)
                ?: throw error(mount.context, "")
            if (values[0] < 0) {
                throw error(mount.context, "")
            }
            NfsStat(values)
        }

    fun mkdir(path: NfsPath, mode: Int) {
        use(path.authority) { mount ->
            check(mount.context, NfsNative.mkdir(mount.context, path.remoteBytes, mode), path.toString())
        }
        LocalWatchService.onEntryCreated(path)
    }

    fun delete(path: NfsPath) {
        val attributes = stat(path, true)
        use(path.authority) { mount ->
            val rc = if (OsConstants.S_ISDIR(attributes.mode)) {
                NfsNative.rmdir(mount.context, path.remoteBytes)
            } else {
                NfsNative.unlink(mount.context, path.remoteBytes)
            }
            check(mount.context, rc, path.toString())
        }
        LocalWatchService.onEntryDeleted(path)
    }

    fun rename(source: NfsPath, target: NfsPath) {
        use(source.authority) { mount ->
            check(
                mount.context,
                NfsNative.rename(mount.context, source.remoteBytes, target.remoteBytes),
                source.toString()
            )
        }
        LocalWatchService.onEntryDeleted(source)
        LocalWatchService.onEntryCreated(target)
    }

    fun readLink(path: NfsPath): ByteString =
        use(path.authority) { mount ->
            val out = arrayOfNulls<ByteArray>(1)
            check(mount.context, NfsNative.readlink(mount.context, path.remoteBytes, out), path.toString())
            ByteString(out[0]!!)
        }

    fun chmod(path: Path, mode: Int) {
        use(path.authority) { mount ->
            check(mount.context, NfsNative.chmod(mount.context, path.remoteBytes, mode), "")
        }
        LocalWatchService.onEntryModified(path as java8.nio.file.Path)
    }

    fun chown(path: Path, uid: Int, gid: Int) {
        use(path.authority) { mount ->
            check(mount.context, NfsNative.chown(mount.context, path.remoteBytes, uid, gid), "")
        }
        LocalWatchService.onEntryModified(path as java8.nio.file.Path)
    }

    fun utimes(path: Path, atimeSec: Long, atimeNsec: Int, mtimeSec: Long, mtimeNsec: Int) {
        use(path.authority) { mount ->
            check(
                mount.context,
                NfsNative.utimes(
                    mount.context, path.remoteBytes, atimeSec, atimeNsec.toLong(), mtimeSec,
                    mtimeNsec.toLong()
                ),
                ""
            )
        }
        LocalWatchService.onEntryModified(path as java8.nio.file.Path)
    }

    fun access(path: NfsPath, read: Boolean, write: Boolean) {
        var mode = 0
        if (read) {
            mode = mode or R_OK
        }
        if (write) {
            mode = mode or W_OK
        }
        use(path.authority) { mount ->
            check(mount.context, NfsNative.access(mount.context, path.remoteBytes, mode), path.toString())
        }
    }

    fun openByteChannel(
        path: NfsPath,
        flags: Int,
        mode: Int,
        append: Boolean,
        deleteOnClose: Boolean
    ): SeekableByteChannel {
        val file = use(path.authority) { mount ->
            val opened = NfsNative.open(mount.context, path.remoteBytes, flags, mode)
            if (opened == 0L) {
                throw error(mount.context, path.toString())
            }
            mount.files++
            opened
        }
        if (flags and OsConstants.O_CREAT != 0) {
            LocalWatchService.onEntryCreated(path)
        }
        return FileByteChannel(path, file, append, deleteOnClose)
    }

    internal fun pread(path: Path, file: Long, buffer: ByteArray, offset: Long): Int =
        use(path.authority) { mount ->
            val rc = NfsNative.pread(mount.context, file, buffer, buffer.size, offset)
            check(mount.context, rc, "")
            rc
        }

    internal fun pwrite(path: Path, file: Long, buffer: ByteArray, offset: Long): Int =
        use(path.authority) { mount ->
            val rc = NfsNative.pwrite(mount.context, file, buffer, buffer.size, offset)
            check(mount.context, rc, "")
            rc
        }

    internal fun fsync(path: Path, file: Long) {
        use(path.authority) { mount ->
            check(mount.context, NfsNative.fsync(mount.context, file), "")
        }
    }

    internal fun ftruncate(path: Path, file: Long, size: Long) {
        use(path.authority) { mount ->
            check(mount.context, NfsNative.ftruncate(mount.context, file, size), "")
        }
    }

    internal fun fstatSize(path: Path, file: Long): Long =
        use(path.authority) { mount ->
            val values = NfsNative.fstat(mount.context, file) ?: throw error(mount.context, "")
            if (values[0] < 0) {
                throw error(mount.context, "")
            }
            NfsStat(values).size
        }

    internal fun closeFile(path: Path, file: Long, deleteOnClose: Boolean) {
        use(path.authority) { mount ->
            val closeRc = NfsNative.closeFile(mount.context, file)
            val closeErrno = NfsNative.takeErrno()
            val closeMessage = NfsNative.getError(mount.context)
            val unlinkRc = if (deleteOnClose) {
                NfsNative.unlink(mount.context, path.remoteBytes)
            } else {
                0
            }
            if (mount.files > 0) {
                mount.files--
            }
            if (closeRc < 0) {
                throw exception(closeErrno, closeMessage)
            }
            if (deleteOnClose) {
                check(mount.context, unlinkRc, "")
            }
        }
        if (deleteOnClose) {
            LocalWatchService.onEntryDeleted(path as java8.nio.file.Path)
        }
    }

    fun throwIo(e: ClientException, file: String, other: String? = null): IOException =
        e.toFileSystemException(file, other)

    private const val R_OK = 4
    private const val W_OK = 2
}
