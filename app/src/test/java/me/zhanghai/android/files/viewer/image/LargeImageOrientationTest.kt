/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayInputStream

class LargeImageOrientationTest {
    private val width = 3
    private val height = 2

    /** Where a stored pixel ends up on screen, as defined by the EXIF orientation tag. */
    private fun exifDisplay(orientation: Int, x: Int, y: Int): Pair<Int, Int> {
        val w = width
        val h = height
        return when (orientation) {
            1 -> x to y
            2 -> (w - 1 - x) to y
            3 -> (w - 1 - x) to (h - 1 - y)
            4 -> x to (h - 1 - y)
            5 -> y to x
            6 -> (h - 1 - y) to x
            7 -> (h - 1 - y) to (w - 1 - x)
            8 -> y to (w - 1 - x)
            else -> error(orientation)
        }
    }

    /** What the view does: rotate clockwise, then mirror the view horizontally. */
    private fun viewDisplay(orientation: LargeImageOrientation, x: Int, y: Int): Pair<Int, Int> {
        var w = width
        var h = height
        var px = x
        var py = y
        repeat(orientation.rotation / 90) {
            val nx = h - 1 - py
            py = px
            px = nx
            w = h.also { h = w }
        }
        if (orientation.isMirrored) {
            px = w - 1 - px
        }
        return px to py
    }

    @Test
    fun everyExifOrientationMapsToTheSameScreenImage() {
        for (orientation in 1..8) {
            val largeImageOrientation = LargeImageOrientation.fromExifOrientation(orientation)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    assertEquals(
                        "orientation $orientation, pixel ($x, $y)",
                        exifDisplay(orientation, x, y),
                        viewDisplay(largeImageOrientation, x, y)
                    )
                }
            }
        }
    }

    @Test
    fun missingOrInvalidOrientationIsNormal() {
        for (orientation in listOf(0, 9, -1)) {
            val largeImageOrientation = LargeImageOrientation.fromExifOrientation(orientation)
            assertEquals(SubsamplingScaleImageView.ORIENTATION_0, largeImageOrientation.rotation)
            assertFalse(largeImageOrientation.isMirrored)
        }
    }

    @Test
    fun skipsExifForOtherFormats() {
        // ExifInterface itself needs the Android runtime, so this only covers the format check.
        val actual = LargeImageOrientation.read(ByteArrayInputStream(ByteArray(0)), "image/png")
        assertEquals(SubsamplingScaleImageView.ORIENTATION_0, actual.rotation)
        assertFalse(actual.isMirrored)
    }
}
