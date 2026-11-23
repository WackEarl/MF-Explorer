/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.nfs.client

/**
 * Time limits for one NFS call as the UI sees it, including the one remount and replay after a
 * dead session. Without them a listing without network waited for the 30 s RPC timeout, then
 * for a remount, about 40 s in total (rc1 finding B4).
 */
internal object NfsTimeouts {
    /** RPC timeout for a mounted context. COMMIT of a full write window needs the headroom. */
    const val RPC_TIMEOUT_MILLIS = 30_000

    /** RPC timeout while mounting. Every mount RPC is small. */
    const val MOUNT_TIMEOUT_MILLIS = 10_000

    /** One call including remount and replay ends within this. */
    const val CALL_BUDGET_MILLIS = 32_000

    /** A replay that would get less than this is not worth starting. */
    const val MIN_REPLAY_MILLIS = 5_000

    /**
     * How long the remount and the replayed call may take, or 0 to fail without a replay.
     * The remount takes at most [MOUNT_TIMEOUT_MILLIS] per RPC, the replayed call gets the rest.
     */
    fun replayBudgetMillis(elapsedMillis: Long): Int {
        val left = CALL_BUDGET_MILLIS - elapsedMillis
        return if (left >= MOUNT_TIMEOUT_MILLIS + MIN_REPLAY_MILLIS) left.toInt() else 0
    }

    /** RPC timeout for the replayed call once the remount took [mountMillis]. */
    fun replayTimeoutMillis(replayBudgetMillis: Int, mountMillis: Long): Int =
        (replayBudgetMillis - mountMillis).coerceIn(1_000L, RPC_TIMEOUT_MILLIS.toLong()).toInt()

    /**
     * True when a call should fail at once: Android has no network at all and the server is not
     * on this device.
     */
    fun failWithoutNetwork(networkAvailable: Boolean, host: String): Boolean =
        !networkAvailable && !isLoopback(host)

    /**
     * Whether a lost default network ends the sessions to [host]. A server on this device
     * is still reachable without one.
     */
    fun abortOnNetworkLoss(host: String): Boolean = !isLoopback(host)

    /**
     * Whether a lost network ends the session to [host] whose local address is [localAddress]
     * (null: no session, or unknown). [remainingAddresses] are the addresses of the networks
     * that are still up, null if they could not be read. Only a session bound to an address
     * that is gone is ended, or all of them once no network is left: a NAS reached over a
     * Wi-Fi without internet must not lose its session when mobile data, the default network,
     * goes away.
     */
    fun abortOnNetworkLoss(
        host: String,
        localAddress: ByteArray?,
        remainingAddresses: List<ByteArray>?
    ): Boolean {
        if (!abortOnNetworkLoss(host)) {
            return false
        }
        if (localAddress == null || remainingAddresses.isNullOrEmpty()) {
            return true
        }
        val local = unmapIpv4(localAddress)
        return remainingAddresses.none { unmapIpv4(it).contentEquals(local) }
    }

    /** ::ffff:a.b.c.d as the 4 bytes of a.b.c.d. */
    private fun unmapIpv4(address: ByteArray): ByteArray {
        if (address.size != 16) {
            return address
        }
        for (i in 0 until 10) {
            if (address[i] != 0.toByte()) {
                return address
            }
        }
        if (address[10] != 0xFF.toByte() || address[11] != 0xFF.toByte()) {
            return address
        }
        return address.copyOfRange(12, 16)
    }

    private fun isLoopback(host: String): Boolean {
        val bare = host.removePrefix("[").removeSuffix("]").lowercase()
        return bare == "localhost" || bare == "::1" || bare.startsWith("127.")
    }
}
