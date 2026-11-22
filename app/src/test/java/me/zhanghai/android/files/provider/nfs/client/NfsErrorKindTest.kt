package me.zhanghai.android.files.provider.nfs.client

import android.system.OsConstants
import java8.nio.file.AccessDeniedException
import java8.nio.file.FileSystemException
import java8.nio.file.NoSuchFileException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NfsErrorKindTest {
    @Test
    fun mapsLibnfsAndJniTimeouts() {
        assertEquals(
            NfsErrorKind.TIMEOUT,
            nfsErrorKind(OsConstants.EIO, "command timed out")
        )
        assertEquals(
            NfsErrorKind.TIMEOUT,
            nfsErrorKind(OsConstants.EINTR, "Command timed out")
        )
        assertEquals(
            NfsErrorKind.TIMEOUT,
            nfsErrorKind(OsConstants.EIO, "Timeout reached")
        )
        assertEquals(
            NfsErrorKind.TIMEOUT,
            nfsErrorKind(OsConstants.ETIMEDOUT, "connect() to server failed. Connection timed out(110)")
        )
        assertEquals(NfsErrorKind.TIMEOUT, nfsErrorKind(OsConstants.ETIMEDOUT, null))
        assertEquals(NfsErrorKind.TIMEOUT, nfsErrorKind(OsConstants.ETIME, null))
        assertEquals(NfsErrorKind.TIMEOUT, nfsErrorKind(OsConstants.EINTR, null))
    }

    @Test
    fun mapsConnectionLostMessages() {
        assertEquals(
            NfsErrorKind.CONNECTION_LOST,
            nfsErrorKind(OsConstants.EIO, "nfs_service failed")
        )
        assertEquals(
            NfsErrorKind.CONNECTION_LOST,
            nfsErrorKind(OsConstants.EIO, "Poll failed")
        )
        assertEquals(
            NfsErrorKind.CONNECTION_LOST,
            nfsErrorKind(OsConstants.EIO, "Socket failed with POLLHUP")
        )
        assertEquals(
            NfsErrorKind.CONNECTION_LOST,
            nfsErrorKind(OsConstants.EIO, "trying to write but not connected")
        )
        assertEquals(
            NfsErrorKind.CONNECTION_LOST,
            nfsErrorKind(OsConstants.EIO, "RPC ERROR: Failed to reconnect async")
        )
        assertEquals(
            NfsErrorKind.CONNECTION_LOST,
            nfsErrorKind(OsConstants.EIO, "Remote side closed connection for socket fd 12")
        )
        assertEquals(
            NfsErrorKind.CONNECTION_LOST,
            nfsErrorKind(OsConstants.EIO, "MOUNT failed with RPC_STATUS_ERROR")
        )
        assertEquals(NfsErrorKind.CONNECTION_LOST, nfsErrorKind(OsConstants.ECONNRESET, null))
        assertEquals(NfsErrorKind.CONNECTION_LOST, nfsErrorKind(OsConstants.ENOTCONN, null))
        assertEquals(NfsErrorKind.CONNECTION_LOST, nfsErrorKind(OsConstants.EPIPE, null))
        assertEquals(NfsErrorKind.CONNECTION_LOST, nfsErrorKind(OsConstants.EFAULT, null))
    }

    @Test
    fun closedContextOfAnOpenFileIsAConnectionLoss() {
        // rc1 B5: a cleanup that runs on a dropped context must count as transport failure.
        val kind = nfsErrorKind(OsConstants.EIO, "NFS connection closed")
        assertEquals(NfsErrorKind.CONNECTION_LOST, kind)
        assertTrue(kind!!.isTransportFailure)
    }

    @Test
    fun mapsServerUnreachableMessages() {
        assertEquals(
            NfsErrorKind.SERVER_UNREACHABLE,
            nfsErrorKind(
                OsConstants.ENETUNREACH,
                "connect() to server failed. Network is unreachable(101)"
            )
        )
        assertEquals(
            NfsErrorKind.SERVER_UNREACHABLE,
            nfsErrorKind(OsConstants.EIO, "connect() to server failed. Connection refused(111)")
        )
        assertEquals(
            NfsErrorKind.SERVER_UNREACHABLE,
            nfsErrorKind(OsConstants.EIO, "No route to host")
        )
        assertEquals(NfsErrorKind.SERVER_UNREACHABLE, nfsErrorKind(OsConstants.ECONNREFUSED, null))
        assertEquals(NfsErrorKind.SERVER_UNREACHABLE, nfsErrorKind(OsConstants.ENETUNREACH, null))
        assertEquals(NfsErrorKind.SERVER_UNREACHABLE, nfsErrorKind(OsConstants.EHOSTUNREACH, null))
    }

    @Test
    fun mapsAccessDeniedFromMountAndNfs3() {
        assertEquals(
            NfsErrorKind.ACCESS_DENIED,
            nfsErrorKind(
                OsConstants.EACCES,
                "mount_cb: RPC error: Mount failed with error MNT3ERR_ACCES(13) Permission denied(13)"
            )
        )
        assertEquals(
            NfsErrorKind.ACCESS_DENIED,
            nfsErrorKind(
                OsConstants.EPERM,
                "mount_cb: RPC error: Mount failed with error MNT3ERR_PERM(1) Operation not permitted(1)"
            )
        )
        assertEquals(
            NfsErrorKind.ACCESS_DENIED,
            nfsErrorKind(
                OsConstants.EACCES,
                "NFS: MKDIR of /a/b failed with NFS3ERR_ACCES(-13) (as UID 65534, GID 65534)"
            )
        )
        assertEquals(
            NfsErrorKind.ACCESS_DENIED,
            nfsErrorKind(OsConstants.EACCES, "NFS: ACCESS denied. Required access ")
        )
        assertEquals(NfsErrorKind.ACCESS_DENIED, nfsErrorKind(OsConstants.EACCES, null))
        assertEquals(NfsErrorKind.ACCESS_DENIED, nfsErrorKind(OsConstants.EPERM, null))
    }

    @Test
    fun mapsMissingExportEvenWhenLibnfsUsesEperm() {
        assertEquals(
            NfsErrorKind.EXPORT_NOT_FOUND,
            nfsErrorKind(
                OsConstants.EPERM,
                "mount_cb: RPC error: Mount failed with error MNT3ERR_NOENT(2) Operation not permitted(1)"
            )
        )
    }

    @Test
    fun leavesOrdinaryMissingFilesUnmapped() {
        assertNull(
            nfsErrorKind(
                OsConstants.ENOENT,
                "NFS: Lookup of /missing.bin failed with NFS3ERR_NOENT(-2)"
            )
        )
    }

    @Test
    fun protocolErrorsAreNotTransportFailures() {
        assertFalse(
            nfsErrorKind(
                OsConstants.EACCES,
                "NFS: MKDIR of /reconnect failed with NFS3ERR_ACCES(-13)"
            )!!.isTransportFailure
        )
        assertFalse(
            nfsErrorKind(
                OsConstants.EPERM,
                "mount_cb: RPC error: Mount failed with error MNT3ERR_NOENT(2) Operation not permitted(1)"
            )!!.isTransportFailure
        )
        assertTrue(nfsErrorKind(OsConstants.EIO, "nfs_service failed")!!.isTransportFailure)
        assertTrue(nfsErrorKind(OsConstants.ETIMEDOUT, "command timed out")!!.isTransportFailure)
        assertTrue(
            nfsErrorKind(
                OsConstants.ENETUNREACH,
                "connect() to server failed. Network is unreachable(101)"
            )!!.isTransportFailure
        )
    }

    @Test
    fun toFileSystemExceptionUsesReadableReasonAndKeepsRawCause() {
        val raw = "mount_cb: RPC error: Mount failed with error MNT3ERR_ACCES(13) Permission denied(13)"
        val exception = ClientException(OsConstants.EACCES, raw)
            .toFileSystemException("/export")
        assertTrue(exception is AccessDeniedException)
        assertEquals("Access to the NFS server was denied", exception.reason)
        assertTrue(exception.cause is ClientException)
        assertEquals(raw, exception.cause!!.message)
    }

    @Test
    fun toFileSystemExceptionMapsConnectionLossAndMissingExport() {
        val lost = ClientException(OsConstants.EIO, "nfs_service failed")
            .toFileSystemException("/big/random-1536M.bin")
        assertEquals(FileSystemException::class.java, lost.javaClass)
        assertEquals("Connection to the NFS server was lost", lost.reason)
        assertEquals("nfs_service failed", lost.cause!!.message)

        val export = ClientException(
            OsConstants.EPERM,
            "mount_cb: RPC error: Mount failed with error MNT3ERR_NOENT(2) Operation not permitted(1)"
        ).toFileSystemException("nfs://192.0.2.1/missing")
        assertTrue(export is NoSuchFileException)
        assertEquals("The NFS export was not found", export.reason)
    }

    @Test
    fun unmappedErrorsKeepTheRawReason() {
        val exception = ClientException(
            OsConstants.ENOENT,
            "NFS: Lookup of /missing.bin failed with NFS3ERR_NOENT(-2)"
        ).toFileSystemException("/missing.bin")
        assertTrue(exception is NoSuchFileException)
        assertEquals(
            "NFS: Lookup of /missing.bin failed with NFS3ERR_NOENT(-2)",
            exception.reason
        )
    }
}
