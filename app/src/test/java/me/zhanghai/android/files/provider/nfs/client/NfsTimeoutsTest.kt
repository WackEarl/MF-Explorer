/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.nfs.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** rc1 B4: a listing without network took about 40 s, the limit is 35 s. */
class NfsTimeoutsTest {
    private val limitMillis = 35_000L

    @Test
    fun callThatRanIntoTheRpcTimeoutIsNotReplayed() {
        // rc1: 30 s RPC timeout, then a remount that waited for connect().
        val elapsed = NfsTimeouts.RPC_TIMEOUT_MILLIS.toLong()
        assertEquals(0, NfsTimeouts.replayBudgetMillis(elapsed))
        assertTrue(elapsed < limitMillis)
    }

    @Test
    fun quickTransportFailureIsReplayedWithinTheLimit() {
        val budget = NfsTimeouts.replayBudgetMillis(200)
        assertTrue(budget > 0)
        assertTrue(200 + budget < limitMillis)
    }

    @Test
    fun worstCaseReplayStaysUnderTheLimit() {
        for (elapsed in 0L..NfsTimeouts.CALL_BUDGET_MILLIS step 250) {
            val budget = NfsTimeouts.replayBudgetMillis(elapsed)
            if (budget == 0) {
                continue
            }
            // The remount uses its full RPC timeout, the replay gets what is left.
            val mount = NfsTimeouts.MOUNT_TIMEOUT_MILLIS.toLong()
            val replay = NfsTimeouts.replayTimeoutMillis(budget, mount)
            assertTrue("elapsed $elapsed", elapsed + mount + replay < limitMillis)
            assertTrue(replay >= NfsTimeouts.MIN_REPLAY_MILLIS)
        }
    }

    @Test
    fun replayTimeoutNeverExceedsTheNormalRpcTimeout() {
        assertEquals(
            NfsTimeouts.RPC_TIMEOUT_MILLIS,
            NfsTimeouts.replayTimeoutMillis(NfsTimeouts.CALL_BUDGET_MILLIS, 0)
        )
    }

    @Test
    fun noNetworkFailsAtOnceExceptForThisDevice() {
        assertTrue(NfsTimeouts.failWithoutNetwork(false, "192.168.0.10"))
        assertTrue(NfsTimeouts.failWithoutNetwork(false, "nas.local"))
        assertFalse(NfsTimeouts.failWithoutNetwork(true, "192.168.0.10"))
        assertFalse(NfsTimeouts.failWithoutNetwork(false, "127.0.0.1"))
        assertFalse(NfsTimeouts.failWithoutNetwork(false, "localhost"))
        assertFalse(NfsTimeouts.failWithoutNetwork(false, "[::1]"))
    }

    @Test
    fun noNetworkErrorReadsAsUnreachable() {
        assertEquals(
            NfsErrorKind.SERVER_UNREACHABLE,
            nfsErrorKind(0, "Network is unreachable")
        )
    }

    @Test
    fun lostNetworkEndsSessionsExceptOnThisDevice() {
        // rc2 B5: a copy that lost the network waited for the RPC timeout before it failed.
        assertTrue(NfsTimeouts.abortOnNetworkLoss("192.168.0.10"))
        assertTrue(NfsTimeouts.abortOnNetworkLoss("nas.local"))
        assertTrue(NfsTimeouts.abortOnNetworkLoss("[fe80::1]"))
        assertFalse(NfsTimeouts.abortOnNetworkLoss("127.0.0.1"))
        assertFalse(NfsTimeouts.abortOnNetworkLoss("[::1]"))
        assertFalse(NfsTimeouts.abortOnNetworkLoss("localhost"))
    }

    private fun ip(vararg bytes: Int) = ByteArray(bytes.size) { bytes[it].toByte() }

    private val wifi = ip(192, 0, 2, 20)
    private val mobile = ip(198, 51, 100, 7)

    @Test
    fun lostMobileDataKeepsTheSessionOverWifiWithoutInternet() {
        // NAS on a Wi-Fi without internet, mobile data is the default network and goes away.
        assertFalse(NfsTimeouts.abortOnNetworkLoss("nas.example", wifi, listOf(wifi)))
    }

    @Test
    fun lostWifiEndsTheSessionBoundToIt() {
        assertTrue(NfsTimeouts.abortOnNetworkLoss("nas.example", wifi, listOf(mobile)))
    }

    @Test
    fun noNetworkLeftOrUnknownEndsAllSessions() {
        assertTrue(NfsTimeouts.abortOnNetworkLoss("nas.example", wifi, emptyList()))
        assertTrue(NfsTimeouts.abortOnNetworkLoss("nas.example", wifi, null))
        // No session, or its address could not be read.
        assertTrue(NfsTimeouts.abortOnNetworkLoss("nas.example", null, listOf(wifi)))
    }

    @Test
    fun loopbackSessionIsNeverEnded() {
        assertFalse(NfsTimeouts.abortOnNetworkLoss("127.0.0.1", null, emptyList()))
    }

    @Test
    fun ipv4MappedLocalAddressMatchesItsNetwork() {
        val mapped = ip(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF, 0xFF, 192, 0, 2, 20)
        assertFalse(NfsTimeouts.abortOnNetworkLoss("nas.example", mapped, listOf(wifi)))
        val v6 = ip(0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
        assertFalse(NfsTimeouts.abortOnNetworkLoss("nas.example", v6, listOf(mobile, v6.copyOf())))
        assertTrue(NfsTimeouts.abortOnNetworkLoss("nas.example", v6, listOf(mobile)))
    }
}
