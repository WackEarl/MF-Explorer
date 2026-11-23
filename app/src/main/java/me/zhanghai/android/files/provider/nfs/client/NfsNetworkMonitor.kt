/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.nfs.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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

    @Volatile
    private var connectivityManager: ConnectivityManager? = null

    /**
     * Retiring a context can block on its lock or on libnfs, and the callbacks of every app
     * component share one ConnectivityThread. One worker keeps that thread free and the
     * network events in order. Lives as long as the process, like the callback.
     */
    private val worker: ExecutorService by lazy { newWorker("NfsNetworkMonitor") }

    /**
     * Pending deletes can wait for a mount and an RPC. Their own worker, so they do not hold up
     * the next network event.
     */
    private val deleter: ExecutorService by lazy { newWorker("NfsPendingDeletes") }

    private fun newWorker(name: String): ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, name).apply { isDaemon = true }
        }

    /**
     * False only while Android reports no default network. A missed or late callback must not
     * keep calls failing once a network is back (rc2 finding B3), so an offline state is checked
     * against the active network before a call is failed for it.
     */
    val isNetworkAvailable: Boolean
        get() {
            val state = state
            if (state.isAvailable) {
                return true
            }
            val active = try {
                connectivityManager?.activeNetwork
            } catch (e: SecurityException) {
                Log.w(TAG, "Cannot read the active network", e)
                return true
            }
            if (state.confirm(active)) {
                Log.i(TAG, "A network is active although no callback said so")
                onNetworkChanged(available = true)
            }
            return state.isAvailable
        }

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
        this.connectivityManager = connectivityManager
        try {
            connectivityManager.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (newState.onAvailable(network)) {
                            Log.i(TAG, "Default network changed, dropping idle NFS contexts")
                            onNetworkChanged(available = true)
                        }
                    }

                    override fun onLost(network: Network) {
                        if (newState.onLost(network)) {
                            Log.i(TAG, "Default network lost, dropping idle NFS contexts")
                            onNetworkChanged(available = false, lost = network)
                        }
                    }
                }
            )
        } catch (e: RuntimeException) {
            // SecurityException or too many callbacks: keep the old behavior.
            Log.w(TAG, "Cannot watch the default network", e)
            this.connectivityManager = null
            state = NetworkState(true)
            return
        }
        try {
            // A network that is not the default one can carry a session too, e.g. a NAS on a
            // Wi-Fi without internet while mobile data is the default network. Its loss ends
            // only the sessions bound to it.
            connectivityManager.registerNetworkCallback(
                NetworkRequest.Builder()
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onLost(network: Network) {
                        worker.execute { abortSessionsOf(network) }
                    }
                }
            )
        } catch (e: RuntimeException) {
            // Sessions on a lost non-default network then wait for the RPC timeout as before.
            Log.w(TAG, "Cannot watch all networks", e)
        }
    }

    /**
     * Local addresses of the networks that are up, without [lost], or null if they cannot be
     * read. The lost network can still be listed right after its onLost.
     */
    private fun remainingAddresses(lost: Network?): List<ByteArray>? {
        val connectivityManager = connectivityManager ?: return null
        return try {
            @Suppress("DEPRECATION")
            connectivityManager.allNetworks
                .filter { it != lost }
                .flatMap { network ->
                    connectivityManager.getLinkProperties(network)?.linkAddresses.orEmpty()
                        .map { it.address.address }
                }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot read the remaining networks", e)
            null
        }
    }

    /** Ends the sessions that ran over [lost]. Runs on [worker]. */
    private fun abortSessionsOf(lost: Network?) {
        try {
            Client.abortSessions(remainingAddresses(lost))
        } catch (e: RuntimeException) {
            Log.e(TAG, "Ending the NFS sessions of a lost network failed", e)
        }
    }

    private fun onNetworkChanged(available: Boolean, lost: Network? = null) {
        worker.execute {
            if (!available) {
                // First, so that a destroy below does not wait for a dead socket either.
                abortSessionsOf(lost)
            }
            try {
                Client.onNetworkChanged()
            } catch (e: RuntimeException) {
                Log.e(TAG, "Handling a network change failed", e)
            }
            if (available) {
                // Partial files of a copy that lost the connection go now, on the new network,
                // not only with the next call on that server (rc2 finding B5a).
                schedulePendingDeletes()
            }
        }
    }

    private val pendingDeletesScheduled = AtomicBoolean()

    /**
     * Runs [Client.deletePendingNow] on its own worker. Also for a delete that was deferred
     * after the network had already come back, which no network event would run any more.
     * Requests while one is queued are merged.
     */
    fun schedulePendingDeletes() {
        if (!pendingDeletesScheduled.compareAndSet(false, true)) {
            return
        }
        deleter.execute {
            pendingDeletesScheduled.set(false)
            try {
                Client.deletePendingNow()
            } catch (e: RuntimeException) {
                Log.e(TAG, "Running pending NFS deletes failed", e)
            }
        }
    }
}
