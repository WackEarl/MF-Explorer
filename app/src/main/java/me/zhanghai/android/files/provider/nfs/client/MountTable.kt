/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.nfs.client

/**
 * Bookkeeping of one context per server, kept apart from libnfs so it can be tested.
 *
 * A context that has to be replaced (network lost or changed, transport failure) but still has
 * open files is detached: new path calls no longer see it and mount a fresh context, while the
 * open files keep using the context that opened them until they are closed. Before, such a
 * context stayed the current one, so every listing after the network came back went to the dead
 * socket until the last file was closed, which in practice meant an app restart (rc2 finding B3).
 */
internal class MountTable<A : Any, M : MountTable.State<A>> {
    open class State<A>(val authority: A) {
        /** Open file handles on this context. Guarded by the owner's mount lock. */
        var files: Int = 0

        /** False once the context was destroyed. Guarded by the owner's mount lock. */
        var alive: Boolean = true

        /** Out of service for new calls; only its open files still use it. */
        @Volatile
        var detached: Boolean = false
            internal set

        /** Must be replaced before the next path call, see [MountTable]. */
        @Volatile
        var stale: Boolean = false
    }

    private val current = HashMap<A, M>()
    private val handles = HashMap<Long, M>()
    private val pins = HashMap<A, Int>()

    @Synchronized
    fun current(authority: A): M? = current[authority]

    @Synchronized
    fun currentMounts(): List<M> = current.values.toList()

    /** Makes [mount] the current one, or returns the one another thread installed first. */
    @Synchronized
    fun install(mount: M): M {
        current[mount.authority]?.let { return it }
        current[mount.authority] = mount
        return mount
    }

    @Synchronized
    fun pin(authority: A) {
        pins[authority] = (pins[authority] ?: 0) + 1
    }

    /** Returns the current mount, which may be freed now. */
    @Synchronized
    fun unpin(authority: A): M? {
        val left = (pins[authority] ?: 0) - 1
        if (left <= 0) {
            pins.remove(authority)
        } else {
            pins[authority] = left
        }
        return current[authority]
    }

    @Synchronized
    fun addHandle(file: Long, mount: M) {
        handles[file] = mount
    }

    /**
     * Only if [file] still belongs to [mount]: once closed, the same pointer value can already
     * be a handle another context opened.
     */
    @Synchronized
    fun removeHandle(file: Long, mount: M) {
        if (handles[file] === mount) {
            handles.remove(file)
        }
    }

    /** The context that opened [file]. A handle is only valid there. */
    @Synchronized
    fun mountFor(file: Long): M? = handles[file]

    /**
     * Takes [mount] out of service for new calls. Returns true if nothing is open on it, so the
     * caller destroys the context now; otherwise the last close does (see [canDrop]).
     */
    @Synchronized
    fun retire(mount: M): Boolean {
        if (current[mount.authority] === mount) {
            current.remove(mount.authority)
        }
        mount.detached = true
        return mount.files == 0
    }

    /**
     * Whether [mount] can be destroyed: nothing is open on it, and it is either detached or a
     * current context that no file system pins any more.
     */
    @Synchronized
    fun canDrop(mount: M): Boolean =
        mount.files == 0 && (mount.detached ||
            ((pins[mount.authority] ?: 0) == 0 && current[mount.authority] === mount))
}
