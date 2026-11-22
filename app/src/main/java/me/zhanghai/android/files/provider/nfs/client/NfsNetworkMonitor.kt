/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.nfs.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log

/**
 * Drops idle NFS contexts when the default network is lost or replaced, so the next call mounts
 * on the current network instead of waiting out a timeout on a socket of the old one. Also lets
 * a call fail at once while there is no network at all.
 */
internal object NfsNetworkMonitor {
    private const val TAG = "NfsNetworkMonitor"

    // Unknown until registered: never block calls on that.
    @Volatile
    private var state = NetworkState(true)

    val isNetworkAvailable: Boolean
        get() = state.isAvailable

    fun register(context: Context) {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
            ?: return
        val newState = try {
            NetworkState(connectivityManager.activeNetwork != null)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot read the active network", e)
            return
        }
        state = newState
        try {
            connectivityManager.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (newState.onAvailable(network)) {
                            Log.i(TAG, "Default network changed, dropping idle NFS contexts")
                            Client.onNetworkChanged()
                        }
                    }

                    override fun onLost(network: Network) {
                        if (newState.onLost(network)) {
                            Log.i(TAG, "Default network lost, dropping idle NFS contexts")
                            Client.onNetworkChanged()
                        }
                    }
                }
            )
        } catch (e: RuntimeException) {
            // SecurityException or too many callbacks: keep the old behavior.
            Log.w(TAG, "Cannot watch the default network", e)
            state = NetworkState(true)
        }
    }
}
