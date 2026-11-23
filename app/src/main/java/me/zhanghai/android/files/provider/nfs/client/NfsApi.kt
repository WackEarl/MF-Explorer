/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.nfs.client

/**
 * The libnfs calls [Client] makes, so its bookkeeping can be tested without the native library.
 * [NfsNative] is the only implementation in the app.
 */
internal interface NfsApi {
    fun takeErrno(): Int

    fun getError(context: Long): String

    /** False when the context has no TCP session. A following RPC would block. */
    fun sessionUp(context: Long): Boolean

    fun create(uid: Int, gid: Int, timeoutMs: Int): Long

    /** RPC timeout for calls queued from now on. */
    fun setTimeout(context: Long, timeoutMs: Int)

    fun destroy(context: Long)

    /**
     * Ends the TCP session of [context] from another thread, so calls blocked on it fail now.
     * The context must stay alive meanwhile.
     */
    fun shutdownSocket(context: Long)

    /**
     * The local IP address (4 or 16 bytes) of the TCP session of [context], or null without a
     * session. May run from another thread like [shutdownSocket].
     */
    fun localAddress(context: Long): ByteArray?

    fun mount(
        context: Long,
        host: String,
        exportPath: ByteArray,
        nfsPort: Int,
        mountPort: Int
    ): Int

    fun open(context: Long, path: ByteArray, flags: Int, mode: Int): Long

    fun closeFile(context: Long, file: Long): Int

    fun pread(
        context: Long,
        file: Long,
        buffer: ByteArray,
        length: Int,
        offset: Long,
        pipeline: Boolean
    ): Int

    fun pwrite(
        context: Long,
        file: Long,
        buffer: ByteArray,
        length: Int,
        offset: Long
    ): Int

    fun fsync(context: Long, file: Long): Int

    fun ftruncate(context: Long, file: Long, size: Long): Int

    fun list(
        context: Long,
        path: ByteArray,
        names: ArrayList<ByteArray>,
        stats: ArrayList<LongArray>
    ): Int

    fun stat(context: Long, path: ByteArray, noFollow: Boolean): LongArray?

    fun fstat(context: Long, file: Long): LongArray?

    fun mkdir(context: Long, path: ByteArray, mode: Int): Int

    fun rmdir(context: Long, path: ByteArray): Int

    fun unlink(context: Long, path: ByteArray): Int

    fun rename(context: Long, from: ByteArray, to: ByteArray): Int

    fun readlink(context: Long, path: ByteArray, out: Array<ByteArray?>): Int

    fun access(context: Long, path: ByteArray, mode: Int): Int

    fun chmod(context: Long, path: ByteArray, mode: Int): Int

    fun chown(context: Long, path: ByteArray, uid: Int, gid: Int): Int

    fun utimes(
        context: Long,
        path: ByteArray,
        atimeSec: Long,
        atimeNsec: Long,
        mtimeSec: Long,
        mtimeNsec: Long
    ): Int
}
