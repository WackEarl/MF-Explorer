/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.imageLoader
import coil.load
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.DefaultOnImageEventListener
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder
import java8.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.zhanghai.android.files.coil.fadeIn
import me.zhanghai.android.files.databinding.ImageViewerItemBinding
import me.zhanghai.android.files.ui.SimpleAdapter
import me.zhanghai.android.files.util.fadeInUnsafe
import me.zhanghai.android.files.util.fadeOutUnsafe
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.logFailure
import me.zhanghai.android.files.util.shortAnimTime
import java.util.concurrent.TimeoutException
import kotlin.math.max

internal class ImageViewerAdapter(
    private val lifecycleOwner: LifecycleOwner,
    private val listener: (View) -> Unit,
    private val preloadGate: NeighborPreloadGate,
    private val newLargeImageDecoder: (Path) -> ImageRegionDecoder = { PathImageRegionDecoder(it) },
    private val largeImageDecodeTimeoutMillis: Long = LARGE_IMAGE_DECODE_TIMEOUT_MILLIS,
    private val readImageInfo: suspend (Path) -> ImageInfo = {
        withContext(Dispatchers.IO) { it.loadImageInfo() }
    }
) : SimpleAdapter<Path, ImageViewerAdapter.ViewHolder>() {
    override val hasStableIds: Boolean
        get() = true

    override fun getItemId(position: Int): Long = getItem(position).hashCode().toLong()

    /** Pages whose last load failed, e.g. a neighbor preloaded while the share was offline. */
    private val failedPaths = mutableSetOf<Path>()

    private var recyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        this.recyclerView = null
    }

    /**
     * Loads the page at [position] again if its last load failed, so a page that is shown again
     * does not keep an error from an earlier attempt. Called when the page is selected.
     *
     * ViewPager2 selects a page from a scroll callback of its RecyclerView, where the adapter
     * must not change, so the reload is posted to the next frame.
     */
    fun reloadIfFailed(position: Int) {
        val path = list.getOrNull(position) ?: return
        if (path !in failedPaths) {
            return
        }
        val recyclerView = recyclerView
        if (recyclerView == null) {
            reloadNowIfFailed(path)
        } else {
            recyclerView.post { reloadNowIfFailed(path) }
        }
    }

    private fun reloadNowIfFailed(path: Path) {
        // The list may have changed in the meantime, e.g. after a delete.
        val position = list.indexOf(path)
        if (position != -1 && failedPaths.remove(path)) {
            // A payload keeps the same holder instead of a change animation with a new one.
            notifyItemChanged(position, PAYLOAD_RELOAD)
        }
    }

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
        binding.progress.hide()
        binding.image.dispose()
        binding.largeImage.setOnImageEventListener(null)
        binding.largeImage.recycle()
    }

    private fun loadImage(holder: ViewHolder, path: Path) {
        val binding = holder.binding
        failedPaths.remove(path)
        binding.image.dispose()
        binding.largeImage.setOnImageEventListener(null)
        binding.largeImage.recycle()
        binding.progress.show()
        binding.errorText.fadeOutUnsafe()
        binding.image.isVisible = false
        binding.largeImage.isVisible = false
        holder.loadJob.launch(lifecycleOwner.lifecycleScope) {
            preloadGate.awaitLoad(path)
            val imageInfo = try {
                readImageInfo(path)
            } catch (e: Exception) {
                if (e is CancellationException && !isActive) {
                    // Recycled or bound to another image. Its views belong to the new bind.
                    throw e
                }
                // Includes a CancellationException from the read itself, which must not leave
                // the page loading forever and its neighbors waiting.
                e.logFailure(TAG, "Cannot read the image info of $path")
                showError(binding, path, e)
                preloadGate.onPageLoaded(path)
                return@launch
            }
            loadImageWithInfo(binding, path, imageInfo)
        }
    }

    private suspend fun loadImageWithInfo(
        binding: ImageViewerItemBinding,
        path: Path,
        imageInfo: ImageInfo
    ) {
        if (!imageInfo.shouldUseLargeImageView) {
            binding.image.apply {
                isVisible = true
                load(path to imageInfo.attributes) {
                    fullImage(path, imageInfo.attributes)
                    fadeIn(context.shortAnimTime)
                    listener(
                        onSuccess = { _, _ ->
                            binding.progress.hide()
                            preloadGate.onPageLoaded(path)
                        },
                        onError = { _, result ->
                            result.throwable.logFailure(TAG, "Cannot load $path")
                            showError(binding, path, result.throwable)
                            preloadGate.onPageLoaded(path)
                        }
                    )
                }
            }
        } else {
            loadLargeImage(binding, path, imageInfo)
        }
    }

    /**
     * Ends in the image or the error page, never in a spinner that stays: the view reports a tile
     * it cannot decode only as a tile error, and without its base layer it never calls onReady()
     * or onImageLoaded(). A decoder that does not return at all is given up on after
     * [largeImageDecodeTimeoutMillis], counted from the first tile so that reading the file over
     * the network is not part of it.
     */
    private suspend fun loadLargeImage(
        binding: ImageViewerItemBinding,
        path: Path,
        imageInfo: ImageInfo
    ) {
        val decodeStarted = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        var loaded = false
        fun fail(e: Exception) {
            if (!finished.complete(Unit)) {
                return
            }
            e.logFailure(TAG, "Cannot load $path")
            // Not recycled here: the view's recycle() waits for a tile that is being decoded,
            // which after a timeout may never return. Recycling the holder does it.
            binding.largeImage.setOnImageEventListener(null)
            showError(binding, path, e)
            preloadGate.onPageLoaded(path)
        }
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
                    if (!finished.complete(Unit)) {
                        return
                    }
                    loaded = true
                    binding.progress.hide()
                    preloadGate.onPageLoaded(path)
                }

                override fun onImageLoadError(e: Exception) {
                    fail(e)
                }

                override fun onTileLoadError(e: Exception) {
                    if (loaded) {
                        // A tile for zooming in. The image itself is shown.
                        e.logFailure(TAG, "Cannot load a tile of $path")
                        return
                    }
                    // A tile of the base layer, e.g. a HEIC the system decoder does not support.
                    fail(e)
                }
            })
            // Not our file provider's URI: see PathImageRegionDecoder. The view needs a URI,
            // and the decoder ignores it.
            setRegionDecoderFactory {
                FirstTileListeningDecoder(newLargeImageDecoder(path)) {
                    decodeStarted.complete(Unit)
                }
            }
            val imageSource = ImageSource.uri(LARGE_IMAGE_URI)
            val previewSource = binding.largeImagePreview(path, imageInfo)
            if (previewSource != null) {
                // A preview needs the dimensions of the image.
                imageSource.dimensions(imageInfo.width, imageInfo.height)
            }
            setImageRestoringSavedState(imageSource, previewSource)
        }
        // Not drawn yet, e.g. a neighbor off screen, is not a decoder that hangs.
        select {
            decodeStarted.onAwait {}
            finished.onAwait {}
        }
        if (finished.isCompleted) {
            return
        }
        withTimeoutOrNull(largeImageDecodeTimeoutMillis) { finished.await() } ?: fail(
            TimeoutException(
                "Decoding $path took longer than $largeImageDecodeTimeoutMillis ms"
            )
        )
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
            e.logFailure(TAG, "Cannot use the thumbnail of $path as a preview")
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

    private fun showError(binding: ImageViewerItemBinding, path: Path, throwable: Throwable) {
        failedPaths.add(path)
        binding.progress.hide()
        binding.errorText.text = throwable.toImageErrorMessage(binding.root.context)
        binding.errorText.fadeInUnsafe(true)
        binding.image.isVisible = false
        binding.largeImage.isVisible = false
    }

    class ViewHolder(val binding: ImageViewerItemBinding) : RecyclerView.ViewHolder(binding.root) {
        internal val loadJob = BindJob()
    }

    companion object {
        private const val TAG = "ImageViewerAdapter"

        private val LARGE_IMAGE_URI: Uri = Uri.parse("mfx-large-image:")

        private val PAYLOAD_RELOAD = Any()

        /** Far longer than the base layer of a 100 MP JPEG takes on a slow phone. */
        private const val LARGE_IMAGE_DECODE_TIMEOUT_MILLIS = 30_000L
    }

}
