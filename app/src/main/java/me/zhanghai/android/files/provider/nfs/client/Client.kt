/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.nfs.client

import android.os.Looper
import android.system.OsConstants
import android.util.Log
import java8.nio.channels.SeekableByteChannel
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.provider.common.ByteString
import me.zhanghai.android.files.provider.common.LocalWatchService
import me.zhanghai.android.files.provider.nfs.NfsPath
import java.io.File
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock

/**
 * One mounted libnfs context per authority. Contexts are not thread-safe, and a file handle can
 * only be used on the context that opened it, so every call takes that mount's lock instead of
 * handing out a second context. A pool would deadlock a same-thread copy that has a read channel
 * and a write channel open together.
 */
internal object Client {
    private const val TAG = "NfsClient"

    private val table = MountTable<Authority, Mount>()
    private val incompleteFiles by lazy {
        IncompleteFiles<Authority, ByteString, NfsPath>().apply {
            val file = File(application.noBackupFilesDir, "nfs-pending-deletes")
            restorePending(PendingDeleteStore.load(file))
            onPendingChanged = { PendingDeleteStore.save(file, it) }
        }
    }

    /**
     * [MountTable.State.stale]: the network this context was mounted on is gone, or one of its
     * calls failed in transport. The next path call takes it out of service; open files keep
     * using it until they are closed.
     */
    private class Mount(
        authority: Authority,
        var context: Long,
        val lock: ReentrantLock = ReentrantLock()
    ) : MountTable.State<Authority>(authority) {
        /** Lets a non-pipeline read give way to a list, open or stat waiting for [lock]. */
        val gate = WaiterGate(lock)

        /**
         * Held while [context] is destroyed, and by [abortSessions], which runs while another
         * thread holds [lock] and must not see the context freed under it.
         */
        val destroyLock = ReentrantLock()
    }

    interface Path {
        val authority: Authority
        val remoteBytes: ByteArray
    }

    /** Keeps the mounted context alive while a file system for this authority is open. */
    fun pin(authority: Authority) {
        table.pin(authority)
    }

    /** Drops a file-system pin and frees the context when nothing is using it. */
    fun unpin(authority: Authority) {
        val mount = table.unpin(authority)
        if (mount != null) {
            reap(mount)
        }
    }

    /**
     * The default network is gone. Ends the TCP sessions of all contexts, also those that only
     * open files still use, so calls blocked on them fail now instead of after the RPC timeout:
     * a copy that lost the network showed its error after 32 s instead of 2 s (rc2 finding B5).
     * Does not wait for the mount locks; a context that is being destroyed is skipped.
     */
    fun abortSessions() {
        for (mount in table.allMounts()) {
            if (!NfsTimeouts.abortOnNetworkLoss(mount.authority.host)) {
                // Loopback does not depend on the lost network: keep it in service.
                continue
            }
            mount.stale = true
            if (!mount.destroyLock.tryLock()) {
                continue
            }
            try {
                val context = mount.context
                if (context != 0L) {
                    Log.i(TAG, "Network lost, ending the NFS session to ${mount.authority} " +
                        "(open files: ${mount.files})")
                    NfsNative.shutdownSocket(context)
                }
            } finally {
                mount.destroyLock.unlock()
            }
        }
    }

    /**
     * The default network was lost or replaced. Contexts are taken out of service now, so the
     * next call mounts on the current network. One with open files stays alive for them only.
     * A context with a call in progress is marked stale and replaced by the next [use].
     */
    fun onNetworkChanged() {
        for (mount in table.currentMounts()) {
            mount.stale = true
            if (!mount.lock.tryLock()) {
                Log.i(TAG, "NFS context for ${mount.authority} is busy, replacing it on next use")
                continue
            }
            try {
                if (mount.alive) {
                    Log.i(
                        TAG, "Retiring NFS context for ${mount.authority} after a network " +
                            "change (open files: ${mount.files})"
                    )
                    retireLocked(mount)
                }
            } finally {
                mount.lock.unlock()
            }
        }
    }

    private fun millisSince(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

    private fun checkThread() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "NFS I/O on the main thread" }
    }

    private fun mount(authority: Authority): Mount {
        table.current(authority)?.let { return it }
        Log.i(TAG, "Mounting a new NFS context for $authority")
        val context = NfsNative.create(
            authority.uid, authority.gid, NfsTimeouts.MOUNT_TIMEOUT_MILLIS
        )
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
        NfsNative.setTimeout(context, NfsTimeouts.RPC_TIMEOUT_MILLIS)
        val mount = Mount(authority, context)
        val installed = table.install(mount)
        if (installed !== mount) {
            NfsNative.destroy(context)
        }
        return installed
    }

    /**
     * @param replay run [block] once more after a dead session was dropped.
     * Only for calls that are safe to repeat. A retried mkdir, unlink, rename
     * or close would apply twice or use a file handle from the destroyed
     * context.
     */
    /**
     * @param waiter list, stat and an open outside a thumbnail: a non-pipeline read does not
     * take the mount lock while one of these is waiting for it.
     */
    private inline fun <R> use(
        authority: Authority,
        replay: Boolean = false,
        waiter: Boolean = false,
        block: (Mount) -> R
    ): R {
        checkThread()
        val start = System.nanoTime()
        var transportRetry = true
        var firstFailure: ClientException? = null
        // Remount plus replayed call, or 0 when this is not a replay.
        var replayBudget = 0
        while (true) {
            if (NfsTimeouts.failWithoutNetwork(NfsNetworkMonitor.isNetworkAvailable, authority.host)) {
                val e = exception(OsConstants.ENETUNREACH, "Network is unreachable")
                firstFailure?.let { e.addSuppressed(it) }
                throw e
            }
            val mountStart = System.nanoTime()
            val mount = try {
                mount(authority)
            } catch (e: ClientException) {
                firstFailure?.let { e.addSuppressed(it) }
                throw e
            }
            val replayTimeout = if (replayBudget > 0) {
                NfsTimeouts.replayTimeoutMillis(replayBudget, millisSince(mountStart))
            } else {
                0
            }
            if (waiter) {
                mount.gate.lockAsWaiter()
            } else {
                mount.lock.lock()
            }
            var timeoutChanged = false
            try {
                if (!mount.alive) {
                    continue
                }
                if (mount.stale) {
                    // Open files keep this context; this call and later ones get a new one.
                    Log.i(TAG, "Replacing stale NFS context for $authority (open files: " +
                        "${mount.files})")
                    retireLocked(mount)
                    continue
                }
                try {
                    mount.ensureSession()
                    if (incompleteFiles.hasPending()) {
                        deletePendingLocked(mount)
                    }
                    if (replayTimeout in 1 until NfsTimeouts.RPC_TIMEOUT_MILLIS) {
                        NfsNative.setTimeout(mount.context, replayTimeout)
                        timeoutChanged = true
                    }
                    return block(mount)
                } catch (e: ClientException) {
                    firstFailure?.let { e.addSuppressed(it) }
                    val transport = e.isTransportFailure()
                    if (transport) {
                        Log.w(
                            TAG, "NFS transport failure on $authority (open files: " +
                                "${mount.files}): errno ${e.errno}, ${e.message}"
                        )
                        // Not for new calls any more. Open files stay on it until closed.
                        mount.stale = true
                    }
                    if (transportRetry && transport) {
                        transportRetry = false
                        firstFailure = e
                        retireLocked(mount)
                        if (replay) {
                            // A call that already ran into the RPC timeout would wait for a
                            // remount and a second timeout. Only replay within the budget.
                            replayBudget = NfsTimeouts.replayBudgetMillis(millisSince(start))
                            if (replayBudget > 0) {
                                continue
                            }
                        }
                    }
                    throw e.withCredentials(authority)
                }
            } finally {
                if (timeoutChanged && mount.alive && mount.context != 0L) {
                    NfsNative.setTimeout(mount.context, NfsTimeouts.RPC_TIMEOUT_MILLIS)
                }
                mount.lock.unlock()
                reap(mount)
            }
        }
    }

    /**
     * Removes partial files whose cleanup failed while the server was unreachable, before the
     * call that found it reachable again runs. So a retried copy does not find the partial
     * target. [Mount.lock] must be held.
     */
    private fun deletePendingLocked(mount: Mount) {
        incompleteFiles.drain(mount.authority) { remote, path, maxSize, stillPending ->
            val remoteBytes = remote.borrowBytes()
            val name = path ?: remote
            val values = NfsNative.stat(mount.context, remoteBytes, true)
            if (values == null || values[0] < 0) {
                val e = error(mount.context, "")
                return@drain if (e.isTransportFailure()) {
                    IncompleteFiles.Outcome.RETRY
                } else {
                    IncompleteFiles.Outcome.DONE
                }
            }
            val stat = NfsStat(values)
            if (!IncompleteFiles.isStillOurs(OsConstants.S_ISREG(stat.mode), stat.size, maxSize)) {
                Log.i(TAG, "Not deleting $name later: it changed since the failed write")
                return@drain IncompleteFiles.Outcome.DONE
            }
            // A create, mkdir or rename of this name forgets the entry under this same mount
            // lock, so nothing can take the name over between this check and the unlink.
            if (!stillPending()) {
                Log.i(TAG, "Not deleting $name later: a new file of that name was written")
                return@drain IncompleteFiles.Outcome.DONE
            }
            if (NfsNative.unlink(mount.context, remoteBytes) < 0) {
                val e = error(mount.context, "")
                Log.w(TAG, "Deleting incomplete $name failed: ${e.message}")
                return@drain if (e.isTransportFailure()) {
                    IncompleteFiles.Outcome.RETRY
                } else {
                    IncompleteFiles.Outcome.DONE
                }
            }
            Log.i(TAG, "Deleted incomplete $name left by a lost connection")
            path?.let { LocalWatchService.onEntryDeleted(it) }
            IncompleteFiles.Outcome.DONE
        }
    }

    /**
     * A write to [path] failed because the connection was lost. [maxSize] is the end of what
     * this process wrote to it.
     */
    internal fun markIncomplete(path: NfsPath, maxSize: Long) {
        incompleteFiles.markIncomplete(path.authority, ByteString(path.remoteBytes), path, maxSize)
    }

    /**
     * A new file of that name was created, or it was renamed or replaced, so a pending delete of
     * it must not run any more. Called with [Mount.lock] held, right after the call that took
     * the name over and before the lock is released: the pending deletes also run under it
     * ([deletePendingLocked]), and must never see the new file as the old partial one.
     */
    private fun forgetIncomplete(path: NfsPath) {
        incompleteFiles.forget(path.authority, ByteString(path.remoteBytes))
    }

    /**
     * Takes a context out of service, e.g. because its TCP session died. [Mount.lock] must be
     * held. The next [mount] opens a new one. The context is destroyed now if no file is open
     * on it, otherwise when its last file is closed.
     */
    private fun retireLocked(mount: Mount) {
        if (!table.retire(mount)) {
            return
        }
        destroyLocked(mount)
    }

    private fun destroyLocked(mount: Mount) {
        mount.alive = false
        mount.destroyLock.lock()
        try {
            val context = mount.context
            mount.context = 0L
            if (context != 0L) {
                NfsNative.destroy(context)
            }
        } finally {
            mount.destroyLock.unlock()
        }
    }

    /**
     * A file handle is only valid on the context that opened it. Never mounts
     * a replacement: that would run the handle against a new context.
     */
    private inline fun <R> useExisting(
        file: Long,
        requireSession: Boolean = true,
        yieldToWaiters: Boolean = false,
        block: (Mount) -> R
    ): R {
        checkThread()
        val mount = table.mountFor(file)
            ?: throw exception(OsConstants.EIO, "NFS connection closed")
        if (yieldToWaiters) {
            mount.gate.lockYieldingToWaiters()
        } else {
            mount.lock.lock()
        }
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
     * Frees a context that has no open files and that is either detached or no longer pinned
     * by a file system. Lock order is the mount lock, then [table].
     */
    private fun reap(mount: Mount) {
        mount.lock.lock()
        try {
            if (!mount.alive || !table.canDrop(mount)) {
                return
            }
            if (mount.detached) {
                Log.i(TAG, "Last file closed, destroying retired NFS context for " +
                    "${mount.authority}")
            }
            table.retire(mount)
            destroyLocked(mount)
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

    /**
     * AUTH_SYS sends the configured UID and GID as they are. With no_all_squash the server
     * checks them against its own file owners, so a denied MKDIR or RMDIR is usually a server
     * directory that this UID/GID may not write. Say which identity was refused.
     */
    private fun ClientException.withCredentials(authority: Authority): ClientException {
        if (errno != OsConstants.EACCES && errno != OsConstants.EPERM) {
            return this
        }
        return ClientException(errno, appendCredentials(message, authority.uid, authority.gid))
            .also { it.stackTrace = stackTrace }
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

    /**
     * Runs the deletes that failed while the server was unreachable, e.g. as soon as the
     * network is back, instead of waiting for the next call on that server. Must not run on
     * the main thread.
     */
    fun deletePendingNow() {
        if (!incompleteFiles.hasPending()) {
            return
        }
        for (authority in incompleteFiles.pendingGroups()) {
            try {
                // use() runs the pending deletes of this server before the empty block.
                use(authority) {}
            } catch (e: ClientException) {
                Log.i(TAG, "Pending deletes on $authority wait for the next call: ${e.message}")
            }
        }
    }

    fun list(directory: NfsPath): List<NfsPath> {
        val children = listRemote(directory)
        // A delete that is still pending could not run yet. Its file is about to go.
        return incompleteFiles.withoutPending(directory.authority, children) {
            ByteString(it.remoteBytes)
        }
    }

    private fun listRemote(directory: NfsPath): List<NfsPath> =
        use(directory.authority, replay = true, waiter = true) { mount ->
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
        return use(path.authority, replay = true, waiter = true) { mount ->
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
            forgetIncomplete(path)
        }
        LocalWatchService.onEntryCreated(path)
    }

    fun delete(path: NfsPath) {
        try {
            val attributes = stat(path, true)
            use(path.authority) { mount ->
                val rc = if (OsConstants.S_ISDIR(attributes.mode)) {
                    NfsNative.rmdir(mount.context, path.remoteBytes)
                } else {
                    NfsNative.unlink(mount.context, path.remoteBytes)
                }
                check(mount.context, rc, path.toString())
            }
        } catch (e: ClientException) {
            // The cleanup of a copy that lost the connection: delete it once it is back.
            if (e.isTransportFailure() &&
                incompleteFiles.deferDelete(path.authority, ByteString(path.remoteBytes))) {
                Log.i(TAG, "Will delete incomplete $path once the server is reachable")
                if (NfsNetworkMonitor.isNetworkAvailable) {
                    // The network may be back already, and its event has passed. Without a
                    // network the next onAvailable runs it.
                    NfsNetworkMonitor.schedulePendingDeletes()
                }
            }
            throw e
        }
        forgetIncomplete(path)
        LocalWatchService.onEntryDeleted(path)
    }

    fun rename(source: NfsPath, target: NfsPath) {
        use(source.authority) { mount ->
            check(
                mount.context,
                NfsNative.rename(mount.context, source.remoteBytes, target.remoteBytes),
                source.toString()
            )
            forgetIncomplete(source)
            forgetIncomplete(target)
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
            replay = flags and OsConstants.O_EXCL == 0,
            // A thumbnail open is not a waiter. Otherwise thumbnail reads would only make
            // way for other thumbnails, and a listing would still queue behind them.
            waiter = pipeline
        ) { mount ->
            val opened = NfsNative.open(mount.context, path.remoteBytes, flags, mode)
            if (opened == 0L) {
                throw error(mount.context, path.toString())
            }
            mount.files++
            table.addHandle(opened, mount)
            if (flags and OsConstants.O_CREAT != 0) {
                // A new writer owns the name now. Its own failure marks it again.
                forgetIncomplete(path)
            }
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
        // Bulk reads do not yield. A copy can still hold up a listing for one pipeline read.
        useExisting(file, yieldToWaiters = !pipeline) { mount ->
            val rc = NfsNative.pread(mount.context, file, buffer, buffer.size, offset, pipeline)
            check(mount.context, rc, "")
            rc
        }

    /**
     * Writes the first [length] bytes of [buffer]. A bulk write may only be queued: its error
     * comes back from a later write, fsync or close of this file.
     */
    internal fun pwrite(path: Path, file: Long, buffer: ByteArray, length: Int, offset: Long): Int =
        useExisting(file) { mount ->
            writeAt(mount, file, buffer, length, offset)
        }

    /** Size and write share one lock so a second append cannot land in between. */
    internal fun append(path: Path, file: Long, buffer: ByteArray, length: Int): Int =
        useExisting(file) { mount ->
            val values = NfsNative.fstat(mount.context, file) ?: throw error(mount.context, "")
            if (values[0] < 0) {
                throw error(mount.context, "")
            }
            writeAt(mount, file, buffer, length, NfsStat(values).size)
        }

    /**
     * Writes the first [length] bytes of [buffer] at [offset], retrying a short count under
     * the same mount lock. A later failure returns how many bytes were already committed so
     * the caller can keep only the tail. Append must not fstat again: the prefix is already
     * in the file.
     */
    private fun writeAt(
        mount: Mount,
        file: Long,
        buffer: ByteArray,
        length: Int,
        offset: Long
    ): Int {
        var off = offset
        var pending = buffer
        var pendingLength = length
        var done = 0
        while (pendingLength > 0) {
            val rc = try {
                val n = NfsNative.pwrite(mount.context, file, pending, pendingLength, off)
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
            if (rc >= pendingLength) {
                break
            }
            off += rc
            pending = pending.copyOfRange(rc, pendingLength)
            pendingLength = pending.size
        }
        return done
    }

    internal fun fsync(path: Path, file: Long) {
        useExisting(file) { mount ->
            check(mount.context, NfsNative.fsync(mount.context, file), "")
        }
    }

    internal fun ftruncate(path: Path, file: Long, size: Long) {
        useExisting(file) { mount ->
            check(mount.context, NfsNative.ftruncate(mount.context, file, size), "")
        }
    }

    internal fun fstatSize(path: Path, file: Long): Long =
        useExisting(file) { mount ->
            val values = NfsNative.fstat(mount.context, file) ?: throw error(mount.context, "")
            if (values[0] < 0) {
                throw error(mount.context, "")
            }
            NfsStat(values).size
        }

    internal fun closeFile(path: Path, file: Long, deleteOnClose: Boolean) {
        checkThread()
        val mount = table.mountFor(file)
            ?: throw exception(OsConstants.EIO, "NFS connection closed")
        mount.lock.lock()
        try {
            if (!mount.alive || mount.context == 0L) {
                throw exception(OsConstants.EIO, "NFS connection closed")
            }
            val closeRc = NfsNative.closeFile(mount.context, file)
            val closeErrno = NfsNative.takeErrno()
            val closeMessage = NfsNative.getError(mount.context)
            val unlinkRc = if (deleteOnClose) {
                NfsNative.unlink(mount.context, path.remoteBytes)
            } else {
                0
            }
            if (closeRc < 0) {
                throw exception(closeErrno, closeMessage)
            }
            if (deleteOnClose) {
                check(mount.context, unlinkRc, "")
            }
        } finally {
            // The handle is gone whether the close reached the server or the context is dead
            // already. Otherwise a detached context would never be freed.
            if (table.removeHandle(file, mount) && mount.files > 0) {
                mount.files--
            }
            mount.lock.unlock()
            reap(mount)
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
