/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import android.media.MediaMetadataRetriever
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.zhanghai.android.files.compat.use

/**
 * Lets only one [MediaMetadataRetriever] exist at a time in the app.
 *
 * MediaMetadataRetriever.release() holds a process-wide mutex in libmedia_jni while the media
 * server disconnects the retriever, and the server runs frame decodes of all retrievers one after
 * another. The FinalizerDaemon takes the same mutex for every retriever it finalizes, a released
 * one too. With many retrievers decoding from a slow source such as NFS, a release waited behind
 * all those decodes, the finalizer behind the release, and the FinalizerWatchdogDaemon killed the
 * app after 10 seconds. The media server serializes the decodes anyway, so running one retriever
 * at a time costs no throughput, and the mutex is never held behind another retriever's work.
 *
 * Waiting for the lock suspends, so a cancelled thumbnail request leaves the queue without
 * touching the media server. The retriever is always released before the lock is given up.
 */
@PublishedApi
internal val mediaMetadataRetrieverLock = Mutex()

suspend inline fun <R> withMediaMetadataRetriever(block: (MediaMetadataRetriever) -> R): R =
    mediaMetadataRetrieverLock.withLock { MediaMetadataRetriever().use(block) }

/** For callers on a plain background thread. Never call on the main thread. */
fun <R> withMediaMetadataRetrieverBlocking(block: (MediaMetadataRetriever) -> R): R =
    runBlocking { withMediaMetadataRetriever(block) }
