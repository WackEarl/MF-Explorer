/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import android.graphics.Bitmap
import android.graphics.Rect
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Calls [onFirstTile] once, on the view's decoder thread, when the view asks [decoder] for its
 * first tile, so a decode timeout leaves out the time it took to read the file.
 */
internal class FirstTileListeningDecoder(
    private val decoder: ImageRegionDecoder,
    private val onFirstTile: () -> Unit
) : ImageRegionDecoder by decoder {
    private val started = AtomicBoolean()

    override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
        if (started.compareAndSet(false, true)) {
            onFirstTile()
        }
        return decoder.decodeRegion(sRect, sampleSize)
    }
}
