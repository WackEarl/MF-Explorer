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
import coil.imageLoader
import coil.load
import coil.size.Size
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.DefaultOnImageEventListener
import java8.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
    private val listener: (View) -> Unit,
    private val onPageLoaded: (Path) -> Unit
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
        loadImage(holder, path)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)

        holder.loadJob.cancel()
        val binding = holder.binding
        binding.image.dispose()
        binding.largeImage.recycle()
    }

    private fun loadImage(holder: ViewHolder, path: Path) {
        val binding = holder.binding
        binding.progress.fadeInUnsafe(true)
        binding.errorText.fadeOutUnsafe()
        binding.image.isVisible = false
        binding.largeImage.isVisible = false
        holder.loadJob.launch(lifecycleOwner.lifecycleScope) {
            val imageInfo = try {
                withContext(Dispatchers.IO) { path.loadImageInfo() }
            } catch (e: CancellationException) {
                // Recycled or bound to another image. Its views belong to the new bind.
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                showError(binding, e)
                onPageLoaded(path)
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
                    placeholderMemoryCacheKey(
                        ThumbnailPlaceholder.memoryCacheKey(path, imageInfo.attributes)
                    )
                    listener(
                        onSuccess = { _, _ ->
                            binding.progress.fadeOutUnsafe()
                            onPageLoaded(path)
                        },
                        onError = { _, result ->
                            showError(binding, result.throwable)
                            onPageLoaded(path)
                        }
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
                    // With a preview this comes before the full image has loaded.
                    override fun onReady() {
                        setDoubleTapZoomScale(binding.largeImage.cropScale)
                        binding.largeImage.fadeInUnsafe(true)
                    }

                    override fun onImageLoaded() {
                        binding.progress.fadeOutUnsafe()
                        onPageLoaded(path)
                    }

                    override fun onImageLoadError(e: Exception) {
                        e.printStackTrace()
                        showError(binding, e)
                        onPageLoaded(path)
                    }
                })
                val imageSource = ImageSource.uri(path.fileProviderUri)
                val previewSource = binding.largeImagePreview(path, imageInfo)
                if (previewSource != null) {
                    // A preview needs the dimensions of the image.
                    imageSource.dimensions(imageInfo.width, imageInfo.height)
                }
                setImageRestoringSavedState(imageSource, previewSource)
            }
        }
    }

    private fun ImageViewerItemBinding.largeImagePreview(
        path: Path,
        imageInfo: ImageInfo
    ): ImageSource? {
        val key = ThumbnailPlaceholder.memoryCacheKey(path, imageInfo.attributes)
        val cached = root.context.imageLoader.memoryCache?.get(key)?.bitmap ?: return null
        return try {
            val preview = ThumbnailPlaceholder.toStoredOrientation(
                cached, imageInfo.largeImageOrientation
            )
            // The view recycles a bitmap it was given, but the memory cache owns the cached one.
            if (preview === cached) {
                ImageSource.cachedBitmap(preview)
            } else {
                ImageSource.bitmap(preview)
            }
        } catch (e: Exception) {
            // Only a placeholder.
            e.printStackTrace()
            null
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

    class ViewHolder(val binding: ImageViewerItemBinding) : RecyclerView.ViewHolder(binding.root) {
        internal val loadJob = BindJob()
    }

}
