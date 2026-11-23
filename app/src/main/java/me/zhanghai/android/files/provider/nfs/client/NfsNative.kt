/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.nfs.client

internal object NfsNative : NfsApi {
    init {
        System.loadLibrary("nfsjni")
    }

    override external fun takeErrno(): Int

    override external fun getError(context: Long): String

    /** False when the context has no TCP session. A following RPC would block. */
    override external fun sessionUp(context: Long): Boolean

    override external fun create(uid: Int, gid: Int, timeoutMs: Int): Long

    /** RPC timeout for calls queued from now on. */
    override external fun setTimeout(context: Long, timeoutMs: Int)

    override external fun destroy(context: Long)

    /**
     * Ends the TCP session of [context] from another thread, so calls blocked on it fail now.
     * The context must stay alive meanwhile.
     */
    override external fun shutdownSocket(context: Long)

    /**
     * The local IP address (4 or 16 bytes) of the TCP session of [context], or null without a
     * session. May run from another thread like [shutdownSocket].
     */
    override external fun localAddress(context: Long): ByteArray?

    override external fun mount(
        context: Long,
        host: String,
        exportPath: ByteArray,
        nfsPort: Int,
        mountPort: Int
    ): Int

    override external fun open(context: Long, path: ByteArray, flags: Int, mode: Int): Long

    override external fun closeFile(context: Long, file: Long): Int

    override external fun pread(
        context: Long,
        file: Long,
        buffer: ByteArray,
        length: Int,
        offset: Long,
        pipeline: Boolean
    ): Int

    override external fun pwrite(
        context: Long,
        file: Long,
        buffer: ByteArray,
        length: Int,
        offset: Long
    ): Int

    override external fun fsync(context: Long, file: Long): Int

    override external fun ftruncate(context: Long, file: Long, size: Long): Int

    override external fun list(
        context: Long,
        path: ByteArray,
        names: ArrayList<ByteArray>,
        stats: ArrayList<LongArray>
    ): Int

    override external fun stat(context: Long, path: ByteArray, noFollow: Boolean): LongArray?

    override external fun fstat(context: Long, file: Long): LongArray?

    override external fun mkdir(context: Long, path: ByteArray, mode: Int): Int

    override external fun rmdir(context: Long, path: ByteArray): Int

    override external fun unlink(context: Long, path: ByteArray): Int

    override external fun rename(context: Long, from: ByteArray, to: ByteArray): Int

    override external fun readlink(context: Long, path: ByteArray, out: Array<ByteArray?>): Int

    override external fun access(context: Long, path: ByteArray, mode: Int): Int

    override external fun chmod(context: Long, path: ByteArray, mode: Int): Int

    override external fun chown(context: Long, path: ByteArray, uid: Int, gid: Int): Int

    override external fun utimes(
        context: Long,
        path: ByteArray,
        atimeSec: Long,
        atimeNsec: Long,
        mtimeSec: Long,
        mtimeNsec: Long
    ): Int
}
