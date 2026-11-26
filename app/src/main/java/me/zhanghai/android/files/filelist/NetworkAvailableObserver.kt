/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Calls [onAvailable] on the main thread when a default network becomes available while the
 * owner is started. It also comes right after the start if a network is up.
 *
 * The call is delayed a little, so the NFS client has dropped its contexts on the old network
 * first and the next read mounts on the new one.
 */
internal class NetworkAvailableObserver(
    private val context: Context,
    private val onAvailable: () -> Unit
) : DefaultLifecycleObserver {
    private val handler = Handler(Looper.getMainLooper())
    private val notifyRunnable = Runnable { onAvailable() }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        // On ConnectivityManager's own thread.
        override fun onAvailable(network: Network) {
            handler.removeCallbacks(notifyRunnable)
            handler.postDelayed(notifyRunnable, DELAY_MILLIS)
        }
    }

    private var isRegistered = false

    override fun onStart(owner: LifecycleOwner) {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
            ?: return
        isRegistered = try {
            connectivityManager.registerDefaultNetworkCallback(callback)
            true
        } catch (e: RuntimeException) {
            // SecurityException or too many callbacks: onResume still retries.
            Log.w(TAG, "Cannot watch the default network", e)
            false
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        handler.removeCallbacks(notifyRunnable)
        if (!isRegistered) {
            return
        }
        isRegistered = false
        try {
            context.getSystemService(ConnectivityManager::class.java)
                ?.unregisterNetworkCallback(callback)
        } catch (e: IllegalArgumentException) {
            // Not registered.
            Log.w(TAG, "Cannot unregister the network callback", e)
        }
    }

    companion object {
        private const val TAG = "NetworkAvailableObserver"

        private const val DELAY_MILLIS = 1000L
    }
}
