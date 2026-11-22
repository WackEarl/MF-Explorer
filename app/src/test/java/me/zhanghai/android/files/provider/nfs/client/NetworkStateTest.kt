/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.nfs.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** rc1 B3: after airplane mode the old context stayed in use until the app was restarted. */
class NetworkStateTest {
    @Test
    fun firstCallbackForTheCurrentNetworkKeepsContexts() {
        val state = NetworkState(true)
        assertFalse(state.onAvailable("wifi"))
        assertTrue(state.isAvailable)
    }

    @Test
    fun airplaneModeDropsContextsOnLossAndAgainOnReturn() {
        val state = NetworkState(true)
        state.onAvailable("wifi")
        assertTrue(state.onLost("wifi"))
        assertFalse(state.isAvailable)
        // Wi-Fi comes back as a new Network object.
        assertTrue(state.onAvailable("wifi2"))
        assertTrue(state.isAvailable)
    }

    @Test
    fun switchingDefaultNetworkDropsContexts() {
        val state = NetworkState(true)
        state.onAvailable("wifi")
        assertTrue(state.onAvailable("ethernet"))
        assertTrue(state.isAvailable)
    }

    @Test
    fun repeatedCallbackForTheSameNetworkKeepsContexts() {
        val state = NetworkState(true)
        state.onAvailable("wifi")
        assertFalse(state.onAvailable("wifi"))
    }

    @Test
    fun losingAnOldNetworkAfterASwitchKeepsTheNewOne() {
        val state = NetworkState(true)
        state.onAvailable("wifi")
        state.onAvailable("ethernet")
        assertFalse(state.onLost("wifi"))
        assertTrue(state.isAvailable)
    }

    @Test
    fun startingWithoutNetworkDropsOnFirstNetwork() {
        val state = NetworkState(false)
        assertFalse(state.isAvailable)
        assertTrue(state.onAvailable("wifi"))
        assertTrue(state.isAvailable)
    }
}
