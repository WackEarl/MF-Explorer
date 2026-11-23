/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import java8.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/** Issue #51: neighbors are preloaded only after the current page has loaded. */
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
    private var currentPath: Path? = current
    private var opened = 0
    private val gate = NeighborPreloadGate({ currentPath }) { opened++ }

    @Test
    fun aNeighborLoadingFirstKeepsTheGateShut() {
        gate.onPageLoaded(previous)

        assertFalse(gate.isOpen)
        assertEquals(0, opened)
    }

    @Test
    fun theCurrentPageOpensTheGateOnce() {
        gate.onPageLoaded(current)
        gate.onPageLoaded(current)
        gate.onPageLoaded(previous)

        assertTrue(gate.isOpen)
        assertEquals(1, opened)
    }

    @Test
    fun afterASwipeTheNewCurrentPageOpensIt() {
        gate.onPageLoaded(previous)
        currentPath = previous
        gate.onPageLoaded(previous)

        assertEquals(1, opened)
    }

    @Test
    fun noCurrentPageKeepsTheGateShut() {
        currentPath = null
        gate.onPageLoaded(current)

        assertEquals(0, opened)
    }
}
