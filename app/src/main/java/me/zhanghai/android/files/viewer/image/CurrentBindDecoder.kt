/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder

/**
 * Keeps [decoder] from showing in a large image view that was bound to another image meanwhile.
 *
 * The view reads and initializes its decoder on a background thread and hands it over without
 * checking that it still shows the same image. For a large file on a share, a page swiped past
 * quickly is often still being read when its view is bound to the next image. Without this, the
 * decoder of the previous image could replace the current one and its tiles show on the wrong
 * page. [isCurrent] is the bind's own check and may be called on any thread.
 */
internal class CurrentBindDecoder(
    private val decoder: ImageRegionDecoder,
    private val isCurrent: () -> Boolean
) : ImageRegionDecoder {
    override fun init(context: Context, uri: Uri): Point {
        val size = try {
            decoder.init(context, uri)
        } catch (e: Exception) {
            // Neither is an error of the image that is shown now.
            if (!isCurrent()) {
                decoder.recycle()
                throw StaleDecoderException(e)
            }
            throw e
        }
        if (!isCurrent()) {
            decoder.recycle()
            throw StaleDecoderException(null)
        }
        return size
    }

    override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
        // Handed over just before the view was bound to another image.
        if (!isCurrent()) {
            throw StaleDecoderException(null)
        }
        return decoder.decodeRegion(sRect, sampleSize)
    }

    override fun isReady(): Boolean = decoder.isReady()

    override fun recycle() {
        decoder.recycle()
    }
}

/** From a decoder of an image that its view no longer shows. Not an error of the shown image. */
internal class StaleDecoderException(cause: Throwable?) :
    IllegalStateException("The view shows another image now", cause)
