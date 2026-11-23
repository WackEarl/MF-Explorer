/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.nfs.client

/**
 * Files this process left incomplete because the connection was lost while writing them, and
 * the ones of those whose cleanup delete failed for the same reason. A copy that fails without
 * network cannot remove its partial target right away (rc1 finding B5). The delete is repeated
 * once the server answers again, before the next call on that server runs.
 *
 * A file is only deleted later if it is still a regular file no larger than what this process
 * wrote. A new writer of the same name ([forget]) takes the name over. Pending deletes are
 * handed to [onPendingChanged] so they survive an app restart.
 *
 * @param G one server, e.g. an authority
 * @param K one file on that server
 * @param P what to notify about a delete; null for entries restored after a restart
 */
internal class IncompleteFiles<G : Any, K : Any, P : Any>(private val capacity: Int = 64) {
    class Entry<P>(val path: P?, val maxSize: Long)

    data class Pending<G, K>(val group: G, val key: K, val maxSize: Long)

    enum class Outcome {
        /** Deleted, gone, or not ours any more: forget the entry. */
        DONE,
        /** The server is still unreachable: keep this and the remaining entries. */
        RETRY
    }

    private val incomplete = LinkedHashMap<Pair<G, K>, Entry<P>>()
    private val pending = LinkedHashMap<Pair<G, K>, Entry<P>>()

    @Volatile
    private var pendingCount = 0

    /** Called with all pending deletes whenever they change, under this object's lock. */
    var onPendingChanged: ((List<Pending<G, K>>) -> Unit)? = null

    /** Pending deletes saved by an earlier process. */
    @Synchronized
    fun restorePending(entries: List<Pending<G, K>>) {
        for (entry in entries) {
            pending.putIfAbsent(entry.group to entry.key, Entry(null, entry.maxSize))
        }
        trim(pending)
        pendingCount = pending.size
    }

    /** A write to [path] failed in transport. [maxSize] is the end of what was written. */
    @Synchronized
    fun markIncomplete(group: G, key: K, path: P, maxSize: Long) {
        val id = group to key
        val previous = incomplete.remove(id)
        incomplete[id] = Entry(path, maxOf(maxSize, previous?.maxSize ?: 0L))
        trim(incomplete)
    }

    /** A new file of that name is being written, or it was renamed or replaced. */
    @Synchronized
    fun forget(group: G, key: K) {
        val id = group to key
        incomplete.remove(id)
        if (pending.remove(id) != null) {
            pendingChanged()
        }
    }

    /**
     * Deleting an incomplete file failed in transport. Returns true if it was ours and will be
     * deleted once the server is reachable.
     */
    @Synchronized
    fun deferDelete(group: G, key: K): Boolean {
        val id = group to key
        val entry = incomplete.remove(id) ?: return pending.containsKey(id)
        pending[id] = entry
        trim(pending)
        pendingChanged()
        return true
    }

    fun hasPending(): Boolean = pendingCount > 0

    /** Servers with pending deletes, in the order they failed. */
    @Synchronized
    fun pendingGroups(): List<G> = pending.keys.map { it.first }.distinct()

    /**
     * [items] without the files of [group] that are still waiting for their delete, so a
     * listing does not show a partial file that will be removed (rc2 finding B5a).
     */
    fun <T> withoutPending(group: G, items: List<T>, keyOf: (T) -> K): List<T> {
        if (!hasPending()) {
            return items
        }
        val keys = synchronized(this) {
            pending.keys.filter { it.first == group }.mapTo(HashSet()) { it.second }
        }
        if (keys.isEmpty()) {
            return items
        }
        return items.filterNot { keyOf(it) in keys }
    }

    /**
     * Runs [delete] for each pending file of [group], in the order they failed. Stops at the
     * first [Outcome.RETRY] and keeps that entry and the ones after it.
     *
     * [delete] gets `stillPending`, which tells under this object's lock whether the entry it
     * runs for is still the pending one. It must be asked right before the actual unlink: a new
     * writer of the same name ([forget]) may have taken the name over since the snapshot (rc2
     * review of B5a). The caller serializes [forget] of the same group with the unlink, e.g. by
     * holding the lock of that server's connection for both.
     */
    fun drain(
        group: G,
        delete: (key: K, path: P?, maxSize: Long, stillPending: () -> Boolean) -> Outcome
    ) {
        val entries = synchronized(this) {
            pending.entries.filter { it.key.first == group }.map { it.key to it.value }
        }
        for ((id, entry) in entries) {
            val stillPending = { synchronized(this) { pending[id] === entry } }
            if (!stillPending()) {
                continue
            }
            val outcome = delete(id.second, entry.path, entry.maxSize, stillPending)
            if (outcome == Outcome.RETRY) {
                return
            }
            synchronized(this) {
                // Only drop what we ran; a forget or a new defer in between wins.
                if (pending[id] === entry) {
                    pending.remove(id)
                    pendingChanged()
                }
            }
        }
    }

    private fun pendingChanged() {
        pendingCount = pending.size
        onPendingChanged?.invoke(
            pending.map { (id, entry) -> Pending(id.first, id.second, entry.maxSize) }
        )
    }

    private fun trim(map: LinkedHashMap<Pair<G, K>, Entry<P>>) {
        while (map.size > capacity) {
            map.remove(map.keys.first())
        }
    }

    companion object {
        /** Whether the file found later is still the partial file this process wrote. */
        fun isStillOurs(isRegularFile: Boolean, size: Long, maxSize: Long): Boolean =
            isRegularFile && size <= maxSize
    }
}
