/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import java8.nio.file.Path
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class NeighborPreloadGateTest {
    private fun path(name: String): Path =
        Proxy.newProxyInstance(Path::class.java.classLoader, arrayOf(Path::class.java)) {
            proxy, method, args ->
            when (method.name) {
                "toString" -> name
                "hashCode" -> name.hashCode()
                "equals" -> proxy === args?.get(0)
                else -> throw AssertionError(method.name)
            }
        } as Path

    private val previous = path("1.jpg")
    private val current = path("2.jpg")
    private val next = path("3.jpg")

    @Test
    fun preparedNeighborsWaitWithoutOpeningAFile() = runBlocking {
        val gate = NeighborPreloadGate(current)
        var neighborStarted = false
        val neighbor = launch { gate.awaitLoad(previous); neighborStarted = true }
        yield()
        assertFalse(neighborStarted)
        gate.awaitLoad(current)
        gate.onPageLoaded(next) // An old completion cannot unblock the selected image.
        yield()
        assertFalse(neighborStarted)
        gate.onPageLoaded(current)
        neighbor.join()
        assertTrue(neighborStarted)
    }

    @Test
    fun swipingToAPreparedPageUnblocksItImmediately() = runBlocking {
        val gate = NeighborPreloadGate(current)
        var started = false
        val waiting = launch { gate.awaitLoad(previous); started = true }
        yield()
        assertFalse(started)
        gate.onPageSelected(previous)
        waiting.join()
        assertTrue(started)
    }

    @Test
    fun aNewUnloadedSelectionClosesTheGateAgain() = runBlocking {
        val gate = NeighborPreloadGate(current)
        gate.onPageLoaded(current)
        gate.onPageSelected(previous)
        var neighborStarted = false
        val neighbor = launch { gate.awaitLoad(next); neighborStarted = true }
        yield()
        assertFalse(neighborStarted)
        gate.onPageLoaded(previous)
        neighbor.join()
        assertTrue(neighborStarted)
    }

    @Test
    fun recyclingCancelsASuspendedNeighbor() = runBlocking {
        val gate = NeighborPreloadGate(current)
        val binding = BindJob()
        var started = false
        val neighbor = binding.launch(this) { gate.awaitLoad(next); started = true }
        yield()
        binding.cancel()
        gate.onPageLoaded(current)
        neighbor.join()
        assertTrue(neighbor.isCancelled)
        assertFalse(started)
    }
}
