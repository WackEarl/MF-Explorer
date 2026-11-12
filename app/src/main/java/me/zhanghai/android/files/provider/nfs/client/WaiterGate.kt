/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.nfs.client

import java.io.InterruptedIOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Counts the threads that wait for [lock] in a list, open or stat, so a non-pipeline read can let
 * them go first. The count and the wait share [waitersLock]; a count changed under another lock
 * than the wait would lose a wakeup.
 *
 * Lock order is [lock], then [waitersLock]. [waitersLock] is never held while acquiring [lock].
 * Both lock methods return with [lock] held; the caller unlocks it as usual.
 */
internal class WaiterGate(private val lock: ReentrantLock) {
    private val waitersLock = ReentrantLock()
    private val noWaiters = waitersLock.newCondition()
    private var waiters = 0

    val waiterCount: Int
        get() = waitersLock.withLock { waiters }

    /** For list, open and stat. */
    fun lockAsWaiter() {
        waitersLock.withLock { waiters++ }
        try {
            lock.lock()
        } finally {
            waitersLock.withLock {
                if (--waiters == 0) {
                    noWaiters.signalAll()
                }
            }
        }
    }

    /**
     * For a non-pipeline read. Does not take [lock] while a list, open or stat waits for it. A
     * read that already holds [lock] is not aborted; the waiter gets the lock after it. The count
     * is checked again once [lock] is held, because a waiter may arrive in between.
     */
    @Throws(InterruptedIOException::class)
    fun lockYieldingToWaiters() {
        if (lock.isHeldByCurrentThread) {
            // Waiting here would wait on ourselves.
            lock.lock()
            return
        }
        while (true) {
            waitersLock.withLock {
                while (waiters > 0) {
                    try {
                        noWaiters.await()
                    } catch (e: InterruptedException) {
                        throw InterruptedIOException().apply { initCause(e) }
                    }
                }
            }
            lock.lock()
            if (waitersLock.withLock { waiters == 0 }) {
                return
            }
            lock.unlock()
        }
    }
}
