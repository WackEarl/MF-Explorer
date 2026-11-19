package me.zhanghai.android.files.provider.nfs.client

import org.junit.Assert.assertEquals
import org.junit.Test

class ClientExceptionCredentialsTest {
    @Test
    fun appendsUidAndGid() {
        assertEquals(
            "NFS: MKDIR of /a/b failed with NFS3ERR_ACCES(-13) (as UID 65534, GID 65534)",
            appendCredentials("NFS: MKDIR of /a/b failed with NFS3ERR_ACCES(-13)", 65534, 65534)
        )
    }

    @Test
    fun emptyMessageKeepsOnlyCredentials() {
        assertEquals("(as UID 99, GID 100)", appendCredentials(null, 99, 100))
        assertEquals("(as UID 99, GID 100)", appendCredentials("", 99, 100))
    }
}
