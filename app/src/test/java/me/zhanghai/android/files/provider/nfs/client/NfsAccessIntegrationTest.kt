package me.zhanghai.android.files.provider.nfs.client

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Reproduces "MKDIR failed with NFS3ERR_ACCES(-13)" on move/copy and the same on RMDIR when
 * deleting: with no_all_squash the server checks the client's AUTH_SYS UID/GID (default
 * 65534/65534) against the real owners, and anonuid/anongid do not apply.
 *
 * Skipped unless a host build of libnfsjni is on LD_LIBRARY_PATH and these are set:
 * MFX_NFS_PORT (NFS and MOUNT on one port, e.g. unfsd -n P -m P -p) and MFX_NFS_EXPORT.
 * The export needs rw,no_root_squash and a server that enforces permissions (unfsd as root).
 * The fixture is created and removed as UID 0.
 */
class NfsAccessIntegrationTest {
    private val port = System.getenv("MFX_NFS_PORT")?.toIntOrNull()
    private val export = System.getenv("MFX_NFS_EXPORT")
    private val base = "/mfx-acces-test-${System.nanoTime()}"
    private var root = 0L

    private fun connect(uid: Int, gid: Int): Long {
        val context = NfsNative.create(uid, gid, 10_000)
        val rc = NfsNative.mount(context, "127.0.0.1", export!!.toByteArray(), port!!, port)
        check(rc == 0) { NfsNative.getError(context) }
        return context
    }

    private fun Long.mkdir(path: String) = NfsNative.mkdir(this, path.toByteArray(), 0x1ed)

    private fun Long.rmdir(path: String) = NfsNative.rmdir(this, path.toByteArray())

    @Before
    fun setUp() {
        assumeTrue(port != null && export != null)
        root = connect(0, 0)
        // Like a directory created by root on the server: root:root 0755.
        for (dir in listOf(base, "$base/Folder", "$base/Folder/Sub", "$base/Empty")) {
            assertEquals(NfsNative.getError(root), 0, root.mkdir(dir))
        }
    }

    @After
    fun tearDown() {
        if (root == 0L) {
            return
        }
        for (dir in listOf("$base/Empty/Folder", "$base/Folder/Sub", "$base/Folder", "$base/Empty",
            base)) {
            root.rmdir(dir)
        }
        NfsNative.destroy(root)
    }

    @Test
    fun defaultUidIsDeniedMkdirAndRmdirInRootOwnedDirectory() {
        val nobody = connect(Authority.DEFAULT_UID, Authority.DEFAULT_GID)
        try {
            // Move Folder into a subfolder of its parent: RENAME is refused, the copy fallback
            // then fails at MKDIR exactly as reported.
            assertEquals(-13, NfsNative.rename(
                nobody, "$base/Folder".toByteArray(), "$base/Empty/Folder".toByteArray()
            ))
            assertEquals(-13, nobody.mkdir("$base/Empty/Folder"))
            assertTrue(NfsNative.getError(nobody), NfsNative.getError(nobody)
                .contains("MKDIR of $base/Empty/Folder failed with NFS3ERR_ACCES(-13)"))
            // Delete: RMDIR needs write access to the parent.
            assertEquals(-13, nobody.rmdir("$base/Empty"))
            assertTrue(NfsNative.getError(nobody).contains("NFS3ERR_ACCES(-13)"))
        } finally {
            NfsNative.destroy(nobody)
        }
    }

    @Test
    fun ownerUidMayMkdirAndRmdir() {
        // Same operations as the owner (no_root_squash keeps UID 0) succeed.
        assertEquals(0, root.mkdir("$base/Empty/Folder"))
        assertEquals(0, root.rmdir("$base/Empty/Folder"))
        assertEquals(0, root.rmdir("$base/Empty"))
        assertEquals(0, root.mkdir("$base/Empty"))
    }

    @Test
    fun renameIntoOwnSubfolderIsInvalidNotAccessDenied() {
        // A folder moved into its own subfolder gets EINVAL from the server; the file job then
        // shows "Cannot move a folder into itself" instead of copying.
        assertEquals(-22, NfsNative.rename(
            root, "$base/Folder".toByteArray(), "$base/Folder/Sub/Folder".toByteArray()
        ))
    }
}
