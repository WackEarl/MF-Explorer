/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import androidx.exifinterface.media.ExifInterface
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import java.io.InputStream

/**
 * How [SubsamplingScaleImageView] has to show an image so that it looks as its EXIF orientation
 * says: first rotate clockwise by [rotation], then mirror horizontally if [isMirrored].
 *
 * The view cannot read EXIF itself here. For a content URI it asks the provider for the media
 * store orientation column, which our FileProvider and document providers don't have, and it
 * cannot mirror at all.
 */
internal class LargeImageOrientation(val rotation: Int, val isMirrored: Boolean) {
    companion object {
        val NORMAL = LargeImageOrientation(SubsamplingScaleImageView.ORIENTATION_0, false)

        // The formats Coil reads EXIF for by default (ExifOrientationPolicy.RESPECT_PERFORMANCE).
        // Others would make ExifInterface walk the whole stream, which is slow on a remote file.
        private val EXIF_MIME_TYPES =
            setOf("image/jpeg", "image/webp", "image/heic", "image/heif")

        fun fromExifOrientation(exifOrientation: Int): LargeImageOrientation =
            when (exifOrientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL ->
                    LargeImageOrientation(SubsamplingScaleImageView.ORIENTATION_0, true)
                ExifInterface.ORIENTATION_ROTATE_180 ->
                    LargeImageOrientation(SubsamplingScaleImageView.ORIENTATION_180, false)
                ExifInterface.ORIENTATION_FLIP_VERTICAL ->
                    LargeImageOrientation(SubsamplingScaleImageView.ORIENTATION_180, true)
                ExifInterface.ORIENTATION_TRANSPOSE ->
                    LargeImageOrientation(SubsamplingScaleImageView.ORIENTATION_90, true)
                ExifInterface.ORIENTATION_ROTATE_90 ->
                    LargeImageOrientation(SubsamplingScaleImageView.ORIENTATION_90, false)
                ExifInterface.ORIENTATION_TRANSVERSE ->
                    LargeImageOrientation(SubsamplingScaleImageView.ORIENTATION_270, true)
                ExifInterface.ORIENTATION_ROTATE_270 ->
                    LargeImageOrientation(SubsamplingScaleImageView.ORIENTATION_270, false)
                else -> NORMAL
            }

        fun read(inputStream: InputStream, mimeType: String?): LargeImageOrientation {
            if (mimeType !in EXIF_MIME_TYPES) {
                return NORMAL
            }
            val exifOrientation = ExifInterface(inputStream)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            return fromExifOrientation(exifOrientation)
        }
    }
}
