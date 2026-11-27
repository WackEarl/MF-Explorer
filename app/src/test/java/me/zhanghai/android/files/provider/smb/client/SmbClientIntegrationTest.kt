package me.zhanghai.android.files.provider.smb.client

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File
import java.nio.ByteBuffer
import java.util.EnumSet
import kotlin.random.Random

/**
 * Exercises the SMBJ calls made by [Client] against a real SMB server: share enumeration over
 * DCE/RPC, password, guest and anonymous sessions, directory listing, reads and writes (also at an
 * offset beyond 4 GiB), rename, server-side and streamed copies, and delete.
 *
 * Skipped unless MFX_SMB_PORT and MFX_SMB_PASSWORD_FILE are set. Expects a throwaway Samba server
 * on 127.0.0.1 with "map to guest = Bad User", a share "test" writable by the user "smbtest"
 * (password in MFX_SMB_PASSWORD_FILE), and a share "public" with "guest ok = yes" that guests can
 * write to. Each run works in its own directory and removes it.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SmbClientIntegrationTest {
    @Test
    fun t01_enumeratesSharesOverDcerpc() {
        val shares = list(TestPath(USER, null))
        assertTrue(shares.toString(), shares.containsAll(listOf("test", "public")))
    }

    @Test
    fun t02_writesReadsListsAndRenames() {
        val directory = path(USER, "test", RUN)
        Client.createDirectory(directory)
        val data = Random(42).nextBytes(5 * 1024 * 1024 + 123)
        val file = directory.resolve("a.bin")
        write(file, data)
        val (readData, size) = read(file, 0, data.size + 10)
        assertEquals(data.size.toLong(), size)
        assertArrayEquals(data, readData)
        val information = Client.getPathInformation(file, false) as FileInformation
        assertEquals(data.size.toLong(), information.endOfFile)
        assertEquals(listOf("$RUN\\a.bin"), list(directory))

        val renamedFile = directory.resolve("b.bin")
        Client.rename(file, renamedFile)
        assertEquals(listOf("$RUN\\b.bin"), list(directory))

        // Overwrite a range inside the existing file.
        write(renamedFile, ByteArray(1000) { 7 }, 4096, SMB2CreateDisposition.FILE_OPEN)
        val (range, _) = read(renamedFile, 4000, 1200)
        assertEquals(data[4095], range[95])
        assertTrue(range.copyOfRange(96, 1096).all { it == 7.toByte() })
        assertEquals(data[5096], range[1096])
    }

    @Test
    fun t03_writesAndReadsBeyond4GiB() {
        val file = path(USER, "test", "$RUN\\sparse.bin")
        val offset = 5L * 1024 * 1024 * 1024 + 17
        val data = Random(7).nextBytes(256 * 1024)
        write(file, data, offset)
        val (readData, size) = read(file, offset, data.size)
        assertEquals(offset + data.size, size)
        assertArrayEquals(data, readData)
        assertTrue(read(file, offset - 16, 16).first.all { it == 0.toByte() })
    }

    @Test
    fun t04_copiesOnServerWithinSession() {
        val source = path(USER, "test", "$RUN\\b.bin")
        val target = path(USER, "test", "$RUN\\copy.bin")
        Client.copyFile(source, target, true, false, 200, null)
        assertFilesEqual(source, target)
    }

    @Test
    fun t05_streamsCopyAcrossSessions() {
        val source = path(USER, "test", "$RUN\\b.bin")
        val target = path(GUEST, "public", "$RUN-copy.bin")
        var copiedSize = 0L
        Client.copyFile(source, target, false, false, 0) { copiedSize += it }
        assertFilesEqual(source, target)
        assertEquals(read(source, 0, 0).second, copiedSize)
        Client.delete(target)
    }

    @Test
    fun t06_authenticatesAsGuest() {
        val file = path(GUEST, "public", "$RUN-guest.txt")
        write(file, "guest".toByteArray())
        assertEquals("guest", String(read(file, 0, 100).first))
        Client.delete(file)
    }

    @Test
    fun t07_authenticatesAnonymously() {
        assertTrue(list(TestPath(ANONYMOUS, null)).contains("public"))
        val file = path(ANONYMOUS, "public", "$RUN-anonymous.txt")
        write(file, "anonymous".toByteArray())
        assertEquals("anonymous", String(read(file, 0, 100).first))
        assertTrue(list(path(ANONYMOUS, "public")).contains("$RUN-anonymous.txt"))
        Client.delete(file)
    }

    @Test
    fun t08_deletes() {
        for (name in listOf("b.bin", "copy.bin", "sparse.bin")) {
            Client.delete(path(USER, "test", "$RUN\\$name"))
        }
        Client.delete(path(USER, "test", RUN))
        assertTrue(RUN !in list(path(USER, "test")))
    }

    private data class TestPath(
        override val authority: Authority,
        override val sharePath: Client.Path.SharePath?
    ) : Client.Path {
        override fun resolve(other: String): Client.Path {
            val sharePath = sharePath ?: return TestPath(authority, Client.Path.SharePath(other, ""))
            val path = if (sharePath.path.isEmpty()) other else "${sharePath.path}\\$other"
            return TestPath(authority, Client.Path.SharePath(sharePath.name, path))
        }
    }

    companion object {
        private val PORT = System.getenv("MFX_SMB_PORT")?.toIntOrNull()
        private val PASSWORD_FILE = System.getenv("MFX_SMB_PASSWORD_FILE")
        private val USER = Authority("127.0.0.1", PORT ?: 0, "smbtest", null)
        private val GUEST = Authority("127.0.0.1", PORT ?: 0, "Guest", null)
        private val ANONYMOUS = Authority("127.0.0.1", PORT ?: 0, "", null)
        private val RUN = "mfx-smb-test-${System.nanoTime()}"

        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            assumeTrue(PORT != null && PASSWORD_FILE != null)
            val password = File(PASSWORD_FILE!!).readText().trim()
            Client.authenticator = object : Authenticator {
                override fun getPassword(authority: Authority): String =
                    if (authority == USER) password else ""
            }
        }

        private fun path(authority: Authority, share: String, path: String = ""): Client.Path =
            TestPath(authority, Client.Path.SharePath(share, path))

        private fun write(
            path: Client.Path,
            data: ByteArray,
            position: Long = 0,
            createDisposition: SMB2CreateDisposition = SMB2CreateDisposition.FILE_OVERWRITE_IF
        ) {
            Client.openByteChannel(
                path, EnumSet.of(
                    AccessMask.FILE_READ_DATA, AccessMask.FILE_WRITE_DATA,
                    AccessMask.FILE_READ_ATTRIBUTES, AccessMask.FILE_WRITE_ATTRIBUTES
                ), EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                EnumSet.allOf(SMB2ShareAccess::class.java), createDisposition,
                EnumSet.noneOf(SMB2CreateOptions::class.java), false
            ).use { channel ->
                channel.position(position)
                val buffer = ByteBuffer.wrap(data)
                while (buffer.hasRemaining()) {
                    channel.write(buffer)
                }
            }
        }

        /** Returns up to [length] bytes from [position] and the file size. */
        private fun read(path: Client.Path, position: Long, length: Int): Pair<ByteArray, Long> =
            Client.openByteChannel(
                path, EnumSet.of(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                EnumSet.allOf(SMB2ShareAccess::class.java), SMB2CreateDisposition.FILE_OPEN,
                EnumSet.noneOf(SMB2CreateOptions::class.java), false
            ).use { channel ->
                val size = channel.size()
                channel.position(position)
                val buffer = ByteBuffer.allocate(length)
                while (buffer.hasRemaining() && channel.read(buffer) >= 0) {}
                buffer.array().copyOf(buffer.position()) to size
            }

        private fun assertFilesEqual(expected: Client.Path, actual: Client.Path) {
            val (expectedData, expectedSize) = read(expected, 0, 8 * 1024 * 1024)
            val (actualData, actualSize) = read(actual, 0, 8 * 1024 * 1024)
            assertEquals(expectedSize, actualSize)
            assertArrayEquals(expectedData, actualData)
        }

        /** Returns the share names, or the paths inside the share, of the entries in [path]. */
        private fun list(path: Client.Path): List<String> =
            Client.openDirectoryIterator(path).use { iterator ->
                iterator.asSequence().map {
                    val sharePath = it.sharePath!!
                    sharePath.path.ifEmpty { sharePath.name }
                }.toList()
            }
    }
}
