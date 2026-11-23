/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.nfs.client

import android.system.OsConstants
import me.zhanghai.android.files.provider.nfs.NfsPath
import me.zhanghai.android.files.provider.nfs.createNfsRootPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Client's mount, abort and close bookkeeping against a fake libnfs. rc2 B5: a copy that lost
 * the network waited for the RPC timeout; review of the fixes: a context whose session is dead
 * lived on for its open files until the process ended.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClientSessionTest {
    /** Calls block like a dead socket until [shutdownSocket] ends the session. */
    private class FakeNfs : NfsApi {
        companion object {
            // Unique across tests: Client is one object for the whole run.
            private val nextContext = AtomicLong(100)
            private val nextFile = AtomicLong(100_000)
        }

        @Volatile
        var lastFile = 0L
        val created: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        val destroyed: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        val closedFiles: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        /** Calls on a context after it was destroyed: a use-after-free in the app. */
        val afterDestroy: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private val up = ConcurrentHashMap<Long, Boolean>()
        private val ended = ConcurrentHashMap<Long, CountDownLatch>()
        private val errno = ThreadLocal<Int>()

        @Volatile
        var hangingListContext = 0L
        val listHangs = CountDownLatch(1)

        private fun live(context: Long, call: String) {
            if (context in destroyed) {
                afterDestroy += "$call($context)"
            }
        }

        private fun latch(context: Long) = ended.getOrPut(context) { CountDownLatch(1) }

        private fun fail(context: Long): Int {
            errno.set(OsConstants.EIO)
            return -1
        }

        override fun takeErrno(): Int = (errno.get() ?: 0).also { errno.set(0) }

        override fun getError(context: Long): String = "nfs_service failed"

        override fun sessionUp(context: Long): Boolean {
            live(context, "sessionUp")
            return up[context] == true
        }

        override fun create(uid: Int, gid: Int, timeoutMs: Int): Long {
            val context = nextContext.incrementAndGet()
            up[context] = true
            created += context
            return context
        }

        override fun setTimeout(context: Long, timeoutMs: Int) {
            live(context, "setTimeout")
        }

        override fun destroy(context: Long) {
            live(context, "destroy")
            destroyed += context
        }

        override fun shutdownSocket(context: Long) {
            live(context, "shutdownSocket")
            up[context] = false
            latch(context).countDown()
        }

        override fun localAddress(context: Long): ByteArray? {
            live(context, "localAddress")
            return if (up[context] == true) byteArrayOf(192.toByte(), 0, 2, 20) else null
        }

        override fun mount(
            context: Long,
            host: String,
            exportPath: ByteArray,
            nfsPort: Int,
            mountPort: Int
        ): Int = 0

        override fun open(context: Long, path: ByteArray, flags: Int, mode: Int): Long {
            live(context, "open")
            if (up[context] != true) {
                fail(context)
                return 0L
            }
            return nextFile.incrementAndGet().also { lastFile = it }
        }

        override fun closeFile(context: Long, file: Long): Int {
            live(context, "closeFile")
            closedFiles += file
            return 0
        }

        override fun pread(
            context: Long,
            file: Long,
            buffer: ByteArray,
            length: Int,
            offset: Long,
            pipeline: Boolean
        ): Int {
            live(context, "pread")
            return if (up[context] == true) length else fail(context)
        }

        override fun pwrite(
            context: Long,
            file: Long,
            buffer: ByteArray,
            length: Int,
            offset: Long
        ): Int = length

        override fun fsync(context: Long, file: Long): Int = 0

        override fun ftruncate(context: Long, file: Long, size: Long): Int = 0

        override fun list(
            context: Long,
            path: ByteArray,
            names: ArrayList<ByteArray>,
            stats: ArrayList<LongArray>
        ): Int {
            live(context, "list")
            if (context == hangingListContext) {
                listHangs.countDown()
                // A real call would wait for the 30 s RPC timeout.
                if (!latch(context).await(30, TimeUnit.SECONDS)) {
                    throw AssertionError("list was not ended by abortSessions")
                }
                return fail(context)
            }
            if (up[context] != true) {
                return fail(context)
            }
            names += "a.zip".toByteArray()
            return 0
        }

        override fun stat(context: Long, path: ByteArray, noFollow: Boolean): LongArray? = null

        override fun fstat(context: Long, file: Long): LongArray? = null

        override fun mkdir(context: Long, path: ByteArray, mode: Int): Int = 0

        override fun rmdir(context: Long, path: ByteArray): Int = 0

        override fun unlink(context: Long, path: ByteArray): Int = 0

        override fun rename(context: Long, from: ByteArray, to: ByteArray): Int = 0

        override fun readlink(context: Long, path: ByteArray, out: Array<ByteArray?>): Int = 0

        override fun access(context: Long, path: ByteArray, mode: Int): Int = 0

        override fun chmod(context: Long, path: ByteArray, mode: Int): Int = 0

        override fun chown(context: Long, path: ByteArray, uid: Int, gid: Int): Int = 0

        override fun utimes(
            context: Long,
            path: ByteArray,
            atimeSec: Long,
            atimeNsec: Long,
            mtimeSec: Long,
            mtimeNsec: Long
        ): Int = 0
    }

    private val nfs = FakeNfs()

    // Client refuses I/O on the main thread, which is the test thread under Robolectric.
    private val io = Executors.newSingleThreadExecutor()
    private val io2 = Executors.newSingleThreadExecutor()

    private fun <T> onIo(block: () -> T): T =
        try {
            io.submit(block).get(10, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw e.cause!!
        }

    private var root: NfsPath? = null

    private var authority: Authority? = null

    @Before
    fun setUp() {
        // Robolectric does not start AppProvider, which sets it in the app. Client keeps its
        // pending deletes below the app's files.
        Class.forName("me.zhanghai.android.files.app.AppProviderKt")
            .getDeclaredField("application")
            .apply { isAccessible = true }
            .set(null, RuntimeEnvironment.getApplication())
        Client.nfsForTest = nfs
        // Client is one object for the whole run; one export per test keeps them apart.
        val authority = Authority(
            "nas.example", Authority.PORTMAPPER, "/export/${System.nanoTime()}",
            Authority.DEFAULT_UID, Authority.DEFAULT_GID
        )
        this.authority = authority
        // As an open file system does; otherwise every idle context is freed after its call.
        Client.pin(authority)
        root = authority.createNfsRootPath() as NfsPath
    }

    @After
    fun tearDown() {
        Client.unpin(authority!!)
        io.shutdownNow()
        io2.shutdownNow()
        Client.nfsForTest = null
    }

    private fun openFile(): Long {
        onIo {
            Client.openByteChannel(
                root!!.resolve("a.zip") as NfsPath, OsConstants.O_RDONLY, 0, append = false,
                deleteOnClose = false
            )
        }
        return nfs.lastFile
    }

    @Test
    fun abortEndsABlockedCallAtOnceAndTheNextCallRemounts() {
        // Mount, then make the next list hang on that session like a dead socket.
        onIo { Client.list(root!!) }
        val first = nfs.created.last()
        nfs.hangingListContext = first
        val listing = io2.submit<List<NfsPath>> { Client.list(root!!) }
        assertTrue(nfs.listHangs.await(5, TimeUnit.SECONDS))

        val start = System.nanoTime()
        // The network is gone and no other one is left.
        Client.abortSessions(emptyList())
        // The list is replayed on a fresh context, without waiting for the RPC timeout.
        val children = listing.get(5, TimeUnit.SECONDS)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000)
        assertEquals(listOf("a.zip"), children.map { it.fileName.toString() })
        assertEquals(2, nfs.created.size)
        assertTrue("idle dead context must be destroyed", first in nfs.destroyed)
        assertEquals(emptyList<String>(), nfs.afterDestroy)
    }

    @Test
    fun abortKeepsASessionOnANetworkThatIsStillUp() {
        onIo { Client.list(root!!) }
        val context = nfs.created.last()
        // Mobile data went away; the NAS session runs over the Wi-Fi address that is still up.
        Client.abortSessions(listOf(byteArrayOf(192.toByte(), 0, 2, 20)))
        assertTrue(nfs.sessionUp(context))
        onIo { Client.list(root!!) }
        assertEquals("no remount for a session that was kept", listOf(context), nfs.created)
    }

    @Test
    fun deadContextWithAnOpenFileIsDestroyedAndItsHandleFailsWithEio() {
        val file = openFile()
        val context = nfs.created.last()
        Client.abortSessions(emptyList())
        Client.onNetworkChanged()
        assertTrue("dead context must not wait for its open file", context in nfs.destroyed)

        try {
            onIo { Client.pread(root!!, file, ByteArray(16), 0, pipeline = true) }
            fail("read on a destroyed context")
        } catch (e: ClientException) {
            assertEquals(OsConstants.EIO, e.errno)
        }
        try {
            onIo { Client.closeFile(root!!, file, deleteOnClose = false) }
            fail("close on a destroyed context reports the lost connection")
        } catch (e: ClientException) {
            assertEquals(OsConstants.EIO, e.errno)
        }
        assertFalse("nothing may pass the handle to libnfs again", file in nfs.closedFiles)
        assertEquals(emptyList<String>(), nfs.afterDestroy)

        // New calls get a fresh context.
        onIo { Client.list(root!!) }
        assertEquals(2, nfs.created.size)
    }

    @Test
    fun liveRetiredContextKeepsServingItsOpenFileUntilClosed() {
        val file = openFile()
        val context = nfs.created.last()
        // Default network replaced, the old session still works (e.g. Wi-Fi to Wi-Fi).
        Client.onNetworkChanged()
        assertFalse(context in nfs.destroyed)
        assertEquals(16, onIo { Client.pread(root!!, file, ByteArray(16), 0, pipeline = true) })
        onIo { Client.list(root!!) }
        assertEquals("new calls mount a fresh context", 2, nfs.created.size)

        onIo { Client.closeFile(root!!, file, deleteOnClose = false) }
        assertTrue(file in nfs.closedFiles)
        assertTrue("last close frees the retired context", context in nfs.destroyed)
        assertEquals(emptyList<String>(), nfs.afterDestroy)
    }

    @Test
    fun transportFailureOnADeadSessionDestroysTheDetachedContext() {
        val file = openFile()
        val context = nfs.created.last()
        Client.onNetworkChanged()
        // libnfs gave up reconnecting: no session any more.
        nfs.shutdownSocket(context)
        try {
            onIo { Client.pread(root!!, file, ByteArray(16), 0, pipeline = true) }
            fail("read without a session")
        } catch (e: ClientException) {
            assertTrue(e.isTransportFailure())
        }
        assertTrue(context in nfs.destroyed)
        // The close only books the handle.
        try {
            onIo { Client.closeFile(root!!, file, deleteOnClose = false) }
        } catch (e: ClientException) {
            assertEquals(OsConstants.EIO, e.errno)
        }
        assertFalse(file in nfs.closedFiles)
        assertEquals(emptyList<String>(), nfs.afterDestroy)
    }
}
