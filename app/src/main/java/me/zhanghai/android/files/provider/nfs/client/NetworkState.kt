/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.nfs.client

/**
 * Tracks the default network. A mounted libnfs context keeps the socket it opened on the
 * network that was current at mount time, and libnfs only reconnects in place once. After the
 * default network went away or changed, that context is not worth another RPC timeout.
 *
 * [onAvailable] and [onLost] return true when existing contexts should be dropped.
 */
internal class NetworkState(initiallyAvailable: Boolean) {
    @Volatile
    var isAvailable: Boolean = initiallyAvailable
        private set

    private var current: Any? = null

    @Synchronized
    fun onAvailable(network: Any): Boolean {
        val previous = current
        current = network
        val wasAvailable = isAvailable
        isAvailable = true
        // The first callback only reports the network we already mounted on.
        return previous != null && previous != network || previous == null && !wasAvailable
    }

    @Synchronized
    fun onLost(network: Any): Boolean {
        if (current != null && current != network) {
            // A non-default network that is no longer the default went away.
            return false
        }
        current = null
        isAvailable = false
        return true
    }
}
