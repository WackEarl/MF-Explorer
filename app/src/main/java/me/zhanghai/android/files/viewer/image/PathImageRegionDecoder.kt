/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder
import java8.nio.file.Path
import me.zhanghai.android.files.provider.common.newInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Decodes the large image view's tiles from [path] itself, ignoring the URI the view passes in.
 *
 * The view's own decoder opens a content URI of our file provider. For a remote file, that is a
 * proxy file descriptor served by the system's AppFuse, and its open and close wait in vold under
 * a lock that is shared by the whole system. An open or unmount that hangs there survives the app
 * process. The system service watchdog then restarts the device, and until it does, no app
 * process can start. Swiping on from a large image closes its descriptor and opens the next one
 * right away, which is the pattern that hung. Reading the file here never makes a proxy
 * descriptor.
 *
 * Like the view's decoder for a content URI, [BitmapRegionDecoder] copies the stream into memory.
 * It is closed right after.
 */
internal class PathImageRegionDecoder(
    private val path: Path,
    private val open: (Path) -> InputStream = { it.newInputStream() }
) : ImageRegionDecoder {
    private val bitmapConfig =
        SubsamplingScaleImageView.getPreferredBitmapConfig() ?: Bitmap.Config.RGB_565
    private val lock = ReentrantReadWriteLock(true)
    private var decoder: BitmapRegionDecoder? = null

    @Throws(IOException::class)
    override fun init(context: Context, uri: Uri): Point {
        val decoder = open(path).use { newRegionDecoder(it) }
            ?: throw IOException("Cannot decode $path")
        lock.write { this.decoder = decoder }
        return Point(decoder.width, decoder.height)
    }

    override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap =
        lock.read {
            val decoder = decoder?.takeUnless { it.isRecycled }
                ?: throw IllegalStateException("Cannot decode region after decoder has been recycled")
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = bitmapConfig
            }
            decoder.decodeRegion(sRect, options)
                ?: throw RuntimeException("Cannot decode region of $path")
        }

    override fun isReady(): Boolean = lock.read { decoder?.isRecycled == false }

    override fun recycle() {
        lock.write {
            decoder?.recycle()
            decoder = null
        }
    }

    private fun newRegionDecoder(inputStream: InputStream): BitmapRegionDecoder? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(inputStream)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(inputStream, false)
        }
}
