/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.nfs.client

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.provider.common.UriAuthority

@Parcelize
data class Authority(
    val host: String,
    val nfsPort: Int,
    val export: String,
    val uid: Int,
    val gid: Int
) : Parcelable {
    fun toUriAuthority(): UriAuthority {
        val uriPort = nfsPort.takeIf { it != PORTMAPPER }
        return UriAuthority(null, host, uriPort)
    }

    override fun toString(): String =
        if (nfsPort != PORTMAPPER) "$host:$nfsPort$export" else "$host$export"

    companion object {
        /** Zero lets libnfs ask the portmapper for mountd and the NFS program. */
        const val PORTMAPPER = 0

        /** libnfs itself uses nobody when ANDROID is defined. */
        const val DEFAULT_UID = 65534
        const val DEFAULT_GID = 65534

        fun normalizeExport(export: String): String {
            val trimmed = export.trim()
            require(trimmed.isNotEmpty()) { "Export is empty" }
            val absolute = if (trimmed.startsWith('/')) trimmed else "/$trimmed"
            val stripped = if (absolute.length > 1) absolute.trimEnd('/') else absolute
            return stripped.ifEmpty { "/" }
        }
    }
}
