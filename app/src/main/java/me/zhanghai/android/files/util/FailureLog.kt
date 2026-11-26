/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import android.util.Log
import me.zhanghai.android.files.provider.nfs.client.ClientException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.ClosedByInterruptException
import java.util.concurrent.CancellationException

enum class FailureKind {
    /** A load that was cancelled, e.g. because its item scrolled away. Not logged. */
    CANCELLED,
    /** An I/O error to be expected while a share is offline. Logged as one line. */
    EXPECTED_IO,
    /** Anything else. Logged with its stack trace. */
    UNEXPECTED
}

val Throwable.failureKind: FailureKind
    get() {
        // Also kotlinx.coroutines.CancellationException, which is an alias of this one.
        if (this is CancellationException || this is InterruptedException ||
            this is ClosedByInterruptException ||
            // A socket timeout is an InterruptedIOException too, but not a cancellation.
            (this is InterruptedIOException && this !is SocketTimeoutException)) {
            return FailureKind.CANCELLED
        }
        if (this is IOException && (findCauseByClass<ClientException>() != null ||
                findCauseByClass<ConnectException>() != null ||
                findCauseByClass<NoRouteToHostException>() != null ||
                findCauseByClass<SocketTimeoutException>() != null ||
                findCauseByClass<UnknownHostException>() != null)) {
            return FailureKind.EXPECTED_IO
        }
        return FailureKind.UNEXPECTED
    }

/**
 * Logs a failure without flooding the log with events that are part of normal use: a
 * cancelled load is not logged, an expected I/O error while offline is one line, and only
 * unexpected failures keep their stack trace.
 */
fun Throwable.logFailure(tag: String, message: String) {
    when (failureKind) {
        FailureKind.CANCELLED -> {}
        FailureKind.EXPECTED_IO -> Log.w(tag, "$message: $this")
        FailureKind.UNEXPECTED -> Log.w(tag, message, this)
    }
}
