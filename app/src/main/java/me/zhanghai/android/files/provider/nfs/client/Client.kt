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

    /**
     * @param replay run [block] once more after a dead session was dropped.
     * Only for calls that are safe to repeat. A retried mkdir, unlink, rename
     * or close would apply twice or use a file handle from the destroyed
     * context.
     */
    private inline fun <R> use(
        authority: Authority,
        replay: Boolean = false,
        block: (Mount) -> R
    ): R {
        checkThread()
        var transportRetry = true
        var firstFailure: ClientException? = null
        while (true) {
            val mount = try {
                mount(authority)
            } catch (e: ClientException) {
                firstFailure?.let { e.addSuppressed(it) }
                throw e
            }
            mount.lock.lock()
            try {
                if (!mount.alive) {
                    continue
                }
                // An open file holds an nfsfh from this context. Replaying the
                // call on a fresh mount would use that pointer against a
                // different context. libnfs reconnects the socket in place for
                // that case; here we only rebuild when nothing is open.
                val canRemount = mount.files == 0
                try {
                    mount.ensureSession()
                    return block(mount)
                } catch (e: ClientException) {
                    firstFailure?.let { e.addSuppressed(it) }
                    if (transportRetry && canRemount && e.isTransportFailure()) {
                        transportRetry = false
                        firstFailure = e
                        retireLocked(mount)
                        if (replay) {
                            continue
                        }
                    }
                    throw e
                }
            } finally {
                mount.lock.unlock()
                reap(mount)
            }
        }
    }

    /**
     * Drops a context whose TCP session died. [Mount.lock] must be held.
     * The next [mount] opens a new one.
     */
    private fun retireLocked(mount: Mount) {
        mount.alive = false
        synchronized(mounts) {
            if (mounts[mount.authority] === mount) {
                mounts.remove(mount.authority)
            }
        }
        val context = mount.context
        mount.context = 0L
        if (context != 0L) {
            NfsNative.destroy(context)
        }
    }

    /**
     * A file handle is only valid on the context that opened it. Never mounts
     * a replacement: that would run the handle against a new context.
     */
    private inline fun <R> useExisting(
        authority: Authority,
        requireSession: Boolean = true,
        block: (Mount) -> R
    ): R {
        checkThread()
        val mount = synchronized(mounts) { mounts[authority] }
            ?: throw exception(OsConstants.EIO, "NFS connection closed")
        mount.lock.lock()
        try {
            if (!mount.alive || mount.context == 0L) {
                throw exception(OsConstants.EIO, "NFS connection closed")
            }
            if (requireSession) {
                mount.ensureSession()
            }
            return block(mount)
        } finally {
            mount.lock.unlock()
            reap(mount)
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

    private fun Mount.ensureSession() {
        if (context == 0L || !NfsNative.sessionUp(context)) {
            throw exception(OsConstants.EIO, "nfs_service failed")
        }
    }

    private fun exception(errno: Int, message: String): ClientException {
        val err = if (errno != 0) errno else OsConstants.EIO
        return ClientException(err, message.takeIf { it.isNotEmpty() } ?: "NFS error $err")
    }

    private fun ClientException.isTransportFailure(): Boolean {
        val text = message ?: return false
        // Protocol errors quote the path ("NFS: Lookup of <path> failed...").
        // A name like "reconnect" must not drop the session.
        if (text.contains("NFS:") || text.contains("NFS3ERR")) {
            return false
        }
        return text.contains("nfs_service failed") ||
            text.contains("Poll failed") ||
            text.contains("Socket closed") ||
            text.contains("Socket failed") ||
            text.contains("not connected") ||
            text.contains("Failed to reconnect") ||
            text.contains("reconnect failed") ||
            text.contains("command timed out") ||
            text.contains("Command timed out") ||
            text.contains("Timeout reached") ||
            text.contains("socket error")
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
        use(directory.authority, replay = true) { mount ->
            val names = ArrayList<ByteArray>()
            val stats = ArrayList<LongArray>()
            check(
                mount.context,
                NfsNative.list(mount.context, directory.remoteBytes, names, stats),
                directory.toString()
            )
            names.mapIndexed { index, name ->
                val child = directory.resolve(ByteString(name))
                val values = stats.getOrNull(index)
                if (values != null && values.size >= 11 && values[0] == 0L && values[1] != 0L) {
                    child.cacheListedStat(NfsStat(values))
                }
                child
            }
        }

    fun stat(path: Path, noFollowLinks: Boolean): NfsStat {
        if (noFollowLinks && path is NfsPath) {
            path.takeListedStat()?.let { return it }
        }
        return use(path.authority, replay = true) { mount ->
            val values = NfsNative.stat(mount.context, path.remoteBytes, noFollowLinks)
                ?: throw error(mount.context, "")
            if (values[0] < 0) {
                throw error(mount.context, "")
            }
            NfsStat(values)
        }
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
        use(path.authority, replay = true) { mount ->
            val out = arrayOfNulls<ByteArray>(1)
            check(mount.context, NfsNative.readlink(mount.context, path.remoteBytes, out), path.toString())
            ByteString(out[0]!!)
        }

    fun chmod(path: Path, mode: Int) {
        use(path.authority, replay = true) { mount ->
            check(mount.context, NfsNative.chmod(mount.context, path.remoteBytes, mode), "")
        }
        LocalWatchService.onEntryModified(path as java8.nio.file.Path)
    }

    fun chown(path: Path, uid: Int, gid: Int) {
        use(path.authority, replay = true) { mount ->
            check(mount.context, NfsNative.chown(mount.context, path.remoteBytes, uid, gid), "")
        }
        LocalWatchService.onEntryModified(path as java8.nio.file.Path)
    }

    fun utimes(path: Path, atimeSec: Long, atimeNsec: Int, mtimeSec: Long, mtimeNsec: Int) {
        use(path.authority, replay = true) { mount ->
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
        use(path.authority, replay = true) { mount ->
            check(mount.context, NfsNative.access(mount.context, path.remoteBytes, mode), path.toString())
        }
    }

    fun openByteChannel(
        path: NfsPath,
        flags: Int,
        mode: Int,
        append: Boolean,
        deleteOnClose: Boolean,
        pipeline: Boolean = true
    ): SeekableByteChannel {
        val file = use(
            path.authority,
            // A lost reply did not hand back a handle. O_EXCL is the exception:
            // the create may have landed and a retry would turn it into EEXIST.
            replay = flags and OsConstants.O_EXCL == 0
        ) { mount ->
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
        return FileByteChannel(path, file, append, deleteOnClose, pipeline)
    }

    /**
     * @param pipeline false for one synchronous READ that neither feeds nor uses the sequential
     * read pipeline, and leaves slots a copy already has in flight alone.
     */
    internal fun pread(
        path: Path,
        file: Long,
        buffer: ByteArray,
        offset: Long,
        pipeline: Boolean
    ): Int =
        useExisting(path.authority) { mount ->
            val rc = NfsNative.pread(mount.context, file, buffer, buffer.size, offset, pipeline)
            check(mount.context, rc, "")
            rc
        }

    internal fun pwrite(path: Path, file: Long, buffer: ByteArray, offset: Long): Int =
        useExisting(path.authority) { mount ->
            writeAt(mount, file, buffer, offset)
        }

    /** Size and write share one lock so a second append cannot land in between. */
    internal fun append(path: Path, file: Long, buffer: ByteArray): Int =
        useExisting(path.authority) { mount ->
            val values = NfsNative.fstat(mount.context, file) ?: throw error(mount.context, "")
            if (values[0] < 0) {
                throw error(mount.context, "")
            }
            writeAt(mount, file, buffer, NfsStat(values).size)
        }

    /**
     * Writes [buffer] at [offset], retrying a short count under the same mount lock.
     * A later failure returns how many bytes were already committed so the caller can
     * keep only the tail. Append must not fstat again: the prefix is already in the file.
     */
    private fun writeAt(mount: Mount, file: Long, buffer: ByteArray, offset: Long): Int {
        var off = offset
        var pending = buffer
        var done = 0
        while (pending.isNotEmpty()) {
            val rc = try {
                val n = NfsNative.pwrite(mount.context, file, pending, pending.size, off)
                check(mount.context, n, "")
                n
            } catch (e: ClientException) {
                if (done > 0) {
                    return done
                }
                throw e
            }
            if (rc <= 0) {
                if (done > 0) {
                    return done
                }
                throw exception(OsConstants.EIO, "Short NFS write")
            }
            done += rc
            if (rc >= pending.size) {
                break
            }
            off += rc
            pending = pending.copyOfRange(rc, pending.size)
        }
        return done
    }

    internal fun fsync(path: Path, file: Long) {
        useExisting(path.authority) { mount ->
            check(mount.context, NfsNative.fsync(mount.context, file), "")
        }
    }

    internal fun ftruncate(path: Path, file: Long, size: Long) {
        useExisting(path.authority) { mount ->
            check(mount.context, NfsNative.ftruncate(mount.context, file, size), "")
        }
    }

    internal fun fstatSize(path: Path, file: Long): Long =
        useExisting(path.authority) { mount ->
            val values = NfsNative.fstat(mount.context, file) ?: throw error(mount.context, "")
            if (values[0] < 0) {
                throw error(mount.context, "")
            }
            NfsStat(values).size
        }

    internal fun closeFile(path: Path, file: Long, deleteOnClose: Boolean) {
        useExisting(path.authority, requireSession = false) { mount ->
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
