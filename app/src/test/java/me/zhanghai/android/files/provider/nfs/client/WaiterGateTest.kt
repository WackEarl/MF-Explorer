/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.nfs.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InterruptedIOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

class WaiterGateTest {
    private val lock = ReentrantLock()
    private val gate = WaiterGate(lock)
    private val order = Collections.synchronizedList(mutableListOf<String>())

    private fun thread(name: String, block: () -> Unit): Thread =
        Thread(block, name).apply {
            isDaemon = true
            start()
        }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out" }
            Thread.sleep(1)
        }
    }

    private fun Thread.waitBlocked() =
        waitUntil { state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING }

    @Test
    fun readGoesAfterAWaitingList() {
        lock.lock()
        val list = thread("list") {
            gate.lockAsWaiter()
            order += "list"
            lock.unlock()
        }
        waitUntil { gate.waiterCount == 1 }
        val read = thread("read") {
            gate.lockYieldingToWaiters()
            order += "read"
            lock.unlock()
        }
        read.waitBlocked()
        // The in-flight operation finishes first; nothing is aborted.
        order += "holder"
        lock.unlock()
        list.join(5000)
        read.join(5000)
        assertEquals(listOf("holder", "list", "read"), order)
        assertEquals(0, gate.waiterCount)
    }

    @Test
    fun readWithoutWaitersTakesTheLock() {
        gate.lockYieldingToWaiters()
        assertTrue(lock.isHeldByCurrentThread)
        lock.unlock()
    }

    @Test
    fun readDoesNotWaitOnItself() {
        lock.lock()
        val list = thread("list") { gate.lockAsWaiter(); lock.unlock() }
        waitUntil { gate.waiterCount == 1 }
        // Reentrant: the read already holds the lock the list waits for.
        gate.lockYieldingToWaiters()
        assertEquals(2, lock.holdCount)
        lock.unlock()
        lock.unlock()
        list.join(5000)
        assertFalse(list.isAlive)
    }

    @Test
    fun manyReadsAndWaitersDoNotDeadlock() {
        val failure = AtomicReference<Throwable>()
        val start = CountDownLatch(1)
        val threads = (0 until 8).map { i ->
            thread("t$i") {
                try {
                    start.await()
                    repeat(2000) {
                        if (i % 2 == 0) gate.lockAsWaiter() else gate.lockYieldingToWaiters()
                        lock.unlock()
                    }
                } catch (t: Throwable) {
                    failure.set(t)
                }
            }
        }
        start.countDown()
        threads.forEach { it.join(20_000) }
        assertTrue(threads.none { it.isAlive })
        failure.get()?.let { throw it }
        assertEquals(0, gate.waiterCount)
        assertFalse(lock.isLocked)
    }

    @Test
    fun interruptedReadThrowsWithoutTheLock() {
        lock.lock()
        val list = thread("list") { gate.lockAsWaiter(); lock.unlock() }
        waitUntil { gate.waiterCount == 1 }
        val thrown = AtomicReference<Throwable>()
        val read = thread("read") {
            try {
                gate.lockYieldingToWaiters()
                lock.unlock()
            } catch (t: Throwable) {
                thrown.set(t)
            }
        }
        read.waitBlocked()
        read.interrupt()
        read.join(5000)
        assertTrue(thrown.get() is InterruptedIOException)
        lock.unlock()
        list.join(5000)
        assertFalse(lock.isLocked)
    }
}
