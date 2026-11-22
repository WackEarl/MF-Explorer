/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import me.zhanghai.android.files.provider.nfs.client.NfsErrorKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** rc1 B6: a wrong export path only showed a toast for about three seconds. */
class NfsConnectErrorFieldTest {
    @Test
    fun exportProblemsStayAtTheExportField() {
        assertEquals(NfsConnectErrorField.EXPORT, nfsConnectErrorField(NfsErrorKind.EXPORT_REFUSED))
        assertEquals(NfsConnectErrorField.EXPORT, nfsConnectErrorField(NfsErrorKind.EXPORT_NOT_FOUND))
        assertEquals(NfsConnectErrorField.EXPORT, nfsConnectErrorField(NfsErrorKind.ACCESS_DENIED))
    }

    @Test
    fun networkProblemsStayAtTheHostField() {
        assertEquals(NfsConnectErrorField.HOST, nfsConnectErrorField(NfsErrorKind.SERVER_UNREACHABLE))
        assertEquals(NfsConnectErrorField.HOST, nfsConnectErrorField(NfsErrorKind.TIMEOUT))
        assertEquals(NfsConnectErrorField.HOST, nfsConnectErrorField(NfsErrorKind.CONNECTION_LOST))
        assertEquals(NfsConnectErrorField.HOST, nfsConnectErrorField(null))
    }
}
