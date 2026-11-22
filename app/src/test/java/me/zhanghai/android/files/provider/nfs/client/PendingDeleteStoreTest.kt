/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.nfs.client

import me.zhanghai.android.files.provider.common.toByteString
import org.junit.Assert.assertEquals
import org.junit.Test

class PendingDeleteStoreTest {
    private val nas = Authority("192.0.2.10", 0, "/mnt/user/test", 65534, 65534)

    @Test
    fun roundTripsServerPathAndSize() {
        val entries = listOf(
            IncompleteFiles.Pending(nas, "/r217nn/c100.zip".toByteArray().toByteString(), 7_340_032L),
            // Paths are bytes: spaces, newlines and non-UTF-8 survive.
            IncompleteFiles.Pending(
                Authority("[fd00::2]", 2049, "/export", 1000, 100),
                byteArrayOf(0x2f, 0x61, 0x20, 0x0a, 0xff.toByte()).toByteString(),
                Long.MAX_VALUE
            )
        )
        assertEquals(entries, PendingDeleteStore.decode(PendingDeleteStore.encode(entries)))
    }

    @Test
    fun skipsDamagedLines() {
        val good = IncompleteFiles.Pending(nas, "/a".toByteArray().toByteString(), 1L)
        val text = "garbage\n" + PendingDeleteStore.encode(listOf(good)) + "a b c d e f !!!\n"
        assertEquals(listOf(good), PendingDeleteStore.decode(text))
    }
}
