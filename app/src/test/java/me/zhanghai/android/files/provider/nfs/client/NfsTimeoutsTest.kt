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
}
