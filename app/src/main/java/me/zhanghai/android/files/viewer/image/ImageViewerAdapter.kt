/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import coil.size.Size
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.DefaultOnImageEventListener
import java8.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.coil.fadeIn
import me.zhanghai.android.files.databinding.ImageViewerItemBinding
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.ui.SimpleAdapter
import me.zhanghai.android.files.util.fadeInUnsafe
import me.zhanghai.android.files.util.fadeOutUnsafe
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.shortAnimTime
import kotlin.math.max

class ImageViewerAdapter(
    private val lifecycleOwner: LifecycleOwner,
    private val listener: (View) -> Unit
) : SimpleAdapter<Path, ImageViewerAdapter.ViewHolder>() {
    override val hasStableIds: Boolean
        get() = true

    override fun getItemId(position: Int): Long = getItem(position).hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ImageViewerItemBinding.inflate(parent.context.layoutInflater, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val path = getItem(position)
        val binding = holder.binding
        binding.image.setOnPhotoTapListener { view, _, _ -> listener(view) }
        binding.largeImage.setOnClickListener(listener)
        loadImage(binding, path)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)

        val binding = holder.binding
        binding.image.dispose()
        binding.largeImage.recycle()
    }

    private fun loadImage(binding: ImageViewerItemBinding, path: Path) {
        binding.progress.fadeInUnsafe(true)
        binding.errorText.fadeOutUnsafe()
        binding.image.isVisible = false
        binding.largeImage.isVisible = false
        lifecycleOwner.lifecycleScope.launch {
            val imageInfo = try {
                withContext(Dispatchers.IO) { path.loadImageInfo() }
            } catch (e: Exception) {
                e.printStackTrace()
                showError(binding, e)
                return@launch
            }
            loadImageWithInfo(binding, path, imageInfo)
        }
    }

    private fun loadImageWithInfo(
        binding: ImageViewerItemBinding,
        path: Path,
        imageInfo: ImageInfo
    ) {
        if (!imageInfo.shouldUseLargeImageView) {
            binding.image.apply {
                isVisible = true
                load(path to imageInfo.attributes) {
                    size(Size.ORIGINAL)
                    fadeIn(context.shortAnimTime)
                    listener(
                        onSuccess = { _, _ -> binding.progress.fadeOutUnsafe() },
                        onError = { _, result -> showError(binding, result.throwable) }
                    )
                }
            }
        } else {
            binding.largeImage.apply {
                setDoubleTapZoomDuration(300)
                // ORIENTATION_USE_EXIF only works for file URIs, and the view cannot mirror.
                orientation = imageInfo.largeImageOrientation.rotation
                scaleX = if (imageInfo.largeImageOrientation.isMirrored) -1f else 1f
                // Otherwise OnImageEventListener.onReady() is never called.
                isVisible = true
                alpha = 0f
                setOnImageEventListener(object : DefaultOnImageEventListener() {
                    override fun onReady() {
                        setDoubleTapZoomScale(binding.largeImage.cropScale)
                        binding.progress.fadeOutUnsafe()
                        binding.largeImage.fadeInUnsafe(true)
                    }

                    override fun onImageLoadError(e: Exception) {
                        e.printStackTrace()
                        showError(binding, e)
                    }
                })
                setImageRestoringSavedState(ImageSource.uri(path.fileProviderUri))
            }
        }
    }

    private val SubsamplingScaleImageView.cropScale: Float
        get() {
            val viewWidth = (width - paddingLeft - paddingRight)
            val viewHeight = (height - paddingTop - paddingBottom)
            val orientation = appliedOrientation
            val rotated90Or270 = orientation == SubsamplingScaleImageView.ORIENTATION_90
                || orientation == SubsamplingScaleImageView.ORIENTATION_270
            val imageWidth = if (rotated90Or270) sHeight else sWidth
            val imageHeight = if (rotated90Or270) sWidth else sHeight
            return max(viewWidth.toFloat() / imageWidth, viewHeight.toFloat() / imageHeight)
        }

    private fun showError(binding: ImageViewerItemBinding, throwable: Throwable) {
        binding.progress.fadeOutUnsafe()
        binding.errorText.text = throwable.toString()
        binding.errorText.fadeInUnsafe(true)
        binding.image.isVisible = false
        binding.largeImage.isVisible = false
    }

    class ViewHolder(val binding: ImageViewerItemBinding) : RecyclerView.ViewHolder(binding.root)

}
