/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.nfs.client

internal object NfsNative {
    init {
        System.loadLibrary("nfsjni")
    }

    external fun takeErrno(): Int

    external fun getError(context: Long): String

    /** False when the context has no TCP session. A following RPC would block. */
    external fun sessionUp(context: Long): Boolean

    external fun create(uid: Int, gid: Int, timeoutMs: Int): Long

    external fun destroy(context: Long)

    external fun mount(
        context: Long,
        host: String,
        exportPath: ByteArray,
        nfsPort: Int,
        mountPort: Int
    ): Int

    external fun open(context: Long, path: ByteArray, flags: Int, mode: Int): Long

    external fun closeFile(context: Long, file: Long): Int

    external fun pread(
        context: Long,
        file: Long,
        buffer: ByteArray,
        length: Int,
        offset: Long,
        pipeline: Boolean
    ): Int

    external fun pwrite(
        context: Long,
        file: Long,
        buffer: ByteArray,
        length: Int,
        offset: Long
    ): Int

    external fun fsync(context: Long, file: Long): Int

    external fun ftruncate(context: Long, file: Long, size: Long): Int

    external fun list(
        context: Long,
        path: ByteArray,
        names: ArrayList<ByteArray>,
        stats: ArrayList<LongArray>
    ): Int

    external fun stat(context: Long, path: ByteArray, noFollow: Boolean): LongArray?

    external fun fstat(context: Long, file: Long): LongArray?

    external fun mkdir(context: Long, path: ByteArray, mode: Int): Int

    external fun rmdir(context: Long, path: ByteArray): Int

    external fun unlink(context: Long, path: ByteArray): Int

    external fun rename(context: Long, from: ByteArray, to: ByteArray): Int

    external fun readlink(context: Long, path: ByteArray, out: Array<ByteArray?>): Int

    external fun access(context: Long, path: ByteArray, mode: Int): Int

    external fun chmod(context: Long, path: ByteArray, mode: Int): Int

    external fun chown(context: Long, path: ByteArray, uid: Int, gid: Int): Int

    external fun utimes(
        context: Long,
        path: ByteArray,
        atimeSec: Long,
        atimeNsec: Long,
        mtimeSec: Long,
        mtimeNsec: Long
    ): Int
}
