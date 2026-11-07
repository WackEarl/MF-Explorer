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
        return UriAuthority(null, normalizeHost(host), uriPort)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is Authority) {
            return false
        }
        return normalizeHost(host) == normalizeHost(other.host) && nfsPort == other.nfsPort &&
            export == other.export && uid == other.uid && gid == other.gid
    }

    override fun hashCode(): Int {
        var result = normalizeHost(host).hashCode()
        result = 31 * result + nfsPort
        result = 31 * result + export.hashCode()
        result = 31 * result + uid
        result = 31 * result + gid
        return result
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

        /** IPv6 with and without brackets is the same server. Brackets are what URI encoding needs. */
        fun normalizeHost(host: String): String {
            val trimmed = host.trim()
            val bare = if (trimmed.startsWith('[') && trimmed.endsWith(']') && trimmed.length > 2) {
                trimmed.substring(1, trimmed.length - 1)
            } else {
                trimmed
            }
            return if (bare.contains(':')) "[$bare]" else bare
        }
    }
}
