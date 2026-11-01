/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.nfs

import android.os.Parcelable
import java.time.Instant
import java8.nio.file.attribute.FileTime
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.provider.common.AbstractPosixFileAttributes
import me.zhanghai.android.files.provider.common.ByteString
import me.zhanghai.android.files.provider.common.FileTimeParceler
import me.zhanghai.android.files.provider.common.PosixFileMode
import me.zhanghai.android.files.provider.common.PosixFileModeBit
import me.zhanghai.android.files.provider.common.PosixFileType
import me.zhanghai.android.files.provider.common.PosixGroup
import me.zhanghai.android.files.provider.common.PosixUser
import me.zhanghai.android.files.provider.nfs.client.NfsStat

@Parcelize
internal data class NfsFileAttributes(
    override val lastModifiedTime: @WriteWith<FileTimeParceler> FileTime,
    override val lastAccessTime: @WriteWith<FileTimeParceler> FileTime,
    override val creationTime: @WriteWith<FileTimeParceler> FileTime,
    override val type: PosixFileType,
    override val size: Long,
    override val fileKey: Parcelable,
    override val owner: PosixUser?,
    override val group: PosixGroup?,
    override val mode: Set<PosixFileModeBit>?,
    override val seLinuxContext: ByteString?
) : AbstractPosixFileAttributes() {
    companion object {
        fun from(stat: NfsStat, path: NfsPath): NfsFileAttributes {
            val lastModifiedTime = FileTime.from(Instant.ofEpochSecond(stat.mtimeSec, stat.mtimeNsec.toLong()))
            val lastAccessTime = FileTime.from(Instant.ofEpochSecond(stat.atimeSec, stat.atimeNsec.toLong()))
            val creationTime = lastModifiedTime
            return NfsFileAttributes(
                lastModifiedTime,
                lastAccessTime,
                creationTime,
                PosixFileType.fromMode(stat.mode),
                stat.size,
                path,
                PosixUser(stat.uid, null),
                PosixGroup(stat.gid, null),
                PosixFileMode.fromInt(stat.mode),
                null
            )
        }
    }
}
