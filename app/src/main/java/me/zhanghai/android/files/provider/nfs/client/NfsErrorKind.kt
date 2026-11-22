/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.nfs.client

import android.system.OsConstants
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.application

/**
 * Maps libnfs and JNI errno values and error strings to a translatable message.
 * Message tokens are checked first because libnfs maps MNT3ERR_NOENT to EPERM.
 */
internal enum class NfsErrorKind(
    val messageRes: Int,
    private val fallback: String,
    val isTransportFailure: Boolean
) {
    TIMEOUT(
        R.string.nfs_error_timeout,
        "The NFS request timed out",
        true
    ),
    CONNECTION_LOST(
        R.string.nfs_error_connection_lost,
        "Connection to the NFS server was lost",
        true
    ),
    ACCESS_DENIED(
        R.string.nfs_error_access_denied,
        "Access to the NFS server was denied",
        false
    ),
    SERVER_UNREACHABLE(
        R.string.nfs_error_server_unreachable,
        "The NFS server is unreachable",
        true
    ),
    EXPORT_NOT_FOUND(
        R.string.nfs_error_export_not_found,
        "The NFS export was not found",
        false
    ),
    /**
     * MOUNT refused the export path. Linux mountd answers MNT3ERR_ACCES for a path that is not
     * exported, also when it does not exist, so this is not a permission problem on a file.
     */
    EXPORT_REFUSED(
        R.string.nfs_error_export_refused,
        "The NFS export was not found or is not shared with this device",
        false
    );

    /** About the export path rather than the server address. */
    val isExportProblem: Boolean
        get() = this == EXPORT_NOT_FOUND || this == EXPORT_REFUSED;

    fun userMessage(): String =
        try {
            application.getString(messageRes)
        } catch (_: UninitializedPropertyAccessException) {
            fallback
        }
}

internal fun nfsErrorKind(errno: Int, message: String?): NfsErrorKind? {
    val text = message.orEmpty()
    when {
        text.contains("MNT3ERR_NOENT") -> return NfsErrorKind.EXPORT_NOT_FOUND
        text.contains("MNT3ERR_ACCES") ||
            text.contains("MNT3ERR_PERM") -> return NfsErrorKind.EXPORT_REFUSED
        text.contains("NFS3ERR_ACCES") ||
            text.contains("NFS3ERR_PERM") ||
            text.contains("ACCESS denied") -> return NfsErrorKind.ACCESS_DENIED
        containsIgnoreCase(text, "timed out") ||
            text.contains("Timeout reached") -> return NfsErrorKind.TIMEOUT
        text.contains("Network is unreachable") ||
            text.contains("No route to host") ||
            text.contains("Connection refused") ||
            text.contains("connect() to server failed") ||
            text.contains("connect() to UDP address failed") ->
            return NfsErrorKind.SERVER_UNREACHABLE
        text.contains("nfs_service failed") ||
            text.contains("Poll failed") ||
            text.contains("Socket closed") ||
            text.contains("Socket failed") ||
            text.contains("not connected") ||
            text.contains("NFS connection closed") ||
            text.contains("Failed to reconnect") ||
            text.contains("reconnect failed") ||
            text.contains("socket error") ||
            text.contains("Socket error") ||
            text.contains("Remote side closed") ||
            text.contains("RPC_STATUS_ERROR") ||
            text.contains("RPC ERROR") ||
            text.contains("RPC error") -> return NfsErrorKind.CONNECTION_LOST
    }
    return when (errno) {
        OsConstants.ETIMEDOUT, OsConstants.ETIME, OsConstants.EINTR -> NfsErrorKind.TIMEOUT
        OsConstants.ENETUNREACH, OsConstants.EHOSTUNREACH, OsConstants.ENETDOWN,
        OsConstants.ECONNREFUSED, OsConstants.EADDRNOTAVAIL -> NfsErrorKind.SERVER_UNREACHABLE
        OsConstants.ECONNRESET, OsConstants.ENOTCONN, OsConstants.EPIPE,
        OsConstants.ECONNABORTED, OsConstants.EFAULT -> NfsErrorKind.CONNECTION_LOST
        OsConstants.EACCES, OsConstants.EPERM -> NfsErrorKind.ACCESS_DENIED
        else -> null
    }
}

/** The NFS error behind [throwable], looking through its causes. */
internal fun nfsErrorKindOf(throwable: Throwable): NfsErrorKind? {
    var current: Throwable? = throwable
    var depth = 0
    while (current != null && depth < 8) {
        if (current is ClientException) {
            return nfsErrorKind(current.errno, current.message)
        }
        current = current.cause
        depth++
    }
    return null
}

private fun containsIgnoreCase(text: String, token: String): Boolean =
    text.indexOf(token, ignoreCase = true) >= 0
