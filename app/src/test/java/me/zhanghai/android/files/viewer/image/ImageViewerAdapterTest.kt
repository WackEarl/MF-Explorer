/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import android.os.Looper
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import me.zhanghai.android.files.R
import me.zhanghai.android.files.file.asMimeType
import me.zhanghai.android.files.testing.TestJpegs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Issues #53 and #51 on a real view holder. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ImageViewerAdapterTest {
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    private fun path(name: String): Path =
        Proxy.newProxyInstance(Path::class.java.classLoader, arrayOf(Path::class.java)) {
            proxy, method, args ->
            when (method.name) {
                "toString" -> name
                "hashCode" -> name.hashCode()
                "equals" -> proxy === args?.get(0)
                else -> throw AssertionError("$name touched: ${method.name}")
            }
        } as Path

    private val attributes = Proxy.newProxyInstance(
        BasicFileAttributes::class.java.classLoader, arrayOf(BasicFileAttributes::class.java)
    ) { _, method, _ -> throw UnsupportedOperationException(method.name) } as BasicFileAttributes

    /** For the memory cache key of a placeholder. */
    private val datedAttributes = Proxy.newProxyInstance(
        BasicFileAttributes::class.java.classLoader, arrayOf(BasicFileAttributes::class.java)
    ) { _, method, _ ->
        when (method.name) {
            "lastModifiedTime" -> FileTime.fromMillis(0)
            else -> throw UnsupportedOperationException(method.name)
        }
    } as BasicFileAttributes

    private val first = path("1.jpg")
    private val second = path("2.jpg")

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun holderFor(adapter: ImageViewerAdapter): ImageViewerAdapter.ViewHolder =
        adapter.onCreateViewHolder(FrameLayout(activity), 0)

    /** A holder whose views are on screen, so delayed view callbacks run. */
    private fun attachedHolderFor(adapter: ImageViewerAdapter): ImageViewerAdapter.ViewHolder {
        val parent = FrameLayout(activity)
        activity.setContentView(parent)
        return adapter.onCreateViewHolder(parent, 0).also { parent.addView(it.itemView) }
    }

    private fun advance(millis: Long) =
        shadowOf(Looper.getMainLooper()).idleFor(millis, TimeUnit.MILLISECONDS)

    private val showDelay: Long
        get() = activity.resources.getInteger(R.integer.image_viewer_progress_show_delay).toLong()

    private fun imageInfo() =
        ImageInfo(attributes, 100, 100, "image/jpeg".asMimeType(), LargeImageOrientation.NORMAL)

    @Test
    fun aRebindDuringALoadNeverShowsTheOldImage() {
        val gate = NeighborPreloadGate(first)
        val firstInfo = CompletableDeferred<ImageInfo>()
        val adapter = ImageViewerAdapter(activity, {}, gate) { path ->
            when (path) {
                first -> firstInfo.await()
                else -> throw IOException("second failed")
            }
        }.apply { replace(listOf(first, second)) }
        val holder = holderFor(adapter)

        adapter.onBindViewHolder(holder, 0)
        idle()
        // Swiped on and the holder was reused for the next image while the first still loads.
        gate.onPageSelected(second)
        adapter.onBindViewHolder(holder, 1)
        idle()
        assertEquals("java.io.IOException: second failed", holder.binding.errorText.text)

        // A normal image would be handed to the image view if this result were still applied.
        firstInfo.complete(
            ImageInfo(attributes, 100, 100, "image/jpeg".asMimeType(), LargeImageOrientation.NORMAL)
        )
        idle()

        assertEquals("java.io.IOException: second failed", holder.binding.errorText.text)
        assertFalse(holder.binding.image.isVisible)
        assertFalse(holder.binding.largeImage.isVisible)
    }

    @Test
    fun aCancellationFromTheReadIsAnErrorAndReleasesTheNeighbors() {
        val gate = NeighborPreloadGate(first)
        val adapter = ImageViewerAdapter(activity, {}, gate) {
            // E.g. a cancelled future inside an NFS read, while the page itself is still bound.
            throw CancellationException("read cancelled")
        }.apply { replace(listOf(first, second)) }
        val holder = holderFor(adapter)
        val neighbor = holderFor(adapter)

        adapter.onBindViewHolder(neighbor, 1)
        adapter.onBindViewHolder(holder, 0)
        idle()

        assertTrue(holder.binding.errorText.text.contains("read cancelled"))
        // The neighbor was let through after the current page failed, and failed the same way.
        assertTrue(neighbor.binding.errorText.text.contains("read cancelled"))
    }

    /** Issue #58: a loading indicator over the placeholder, without flashing. */
    @Test
    fun theLoadingIndicatorAppearsAfterTheDelayAndIsDrawnOverTheImage() {
        val gate = NeighborPreloadGate(first)
        val adapter = ImageViewerAdapter(activity, {}, gate) {
            CompletableDeferred<ImageInfo>().await()
        }.apply { replace(listOf(first)) }
        val holder = attachedHolderFor(adapter)

        adapter.onBindViewHolder(holder, 0)
        advance(showDelay / 2)
        assertFalse(holder.binding.progress.isVisible)
        advance(showDelay)

        assertTrue(holder.binding.progress.isVisible)
        // Last in the frame, so the thumbnail placeholder does not cover it.
        val root = holder.binding.root
        assertEquals(root.childCount - 1, root.indexOfChild(holder.binding.progress))
    }

    @Test
    fun aLoadThatEndsWithinTheDelayNeverShowsTheIndicator() {
        val gate = NeighborPreloadGate(first)
        val info = CompletableDeferred<ImageInfo>()
        val adapter = ImageViewerAdapter(activity, {}, gate) {
            info.await()
            throw IOException("gone")
        }.apply { replace(listOf(first)) }
        val holder = attachedHolderFor(adapter)
        var everShown = false
        holder.binding.progress.viewTreeObserver.addOnPreDrawListener {
            everShown = everShown || holder.binding.progress.isVisible
            true
        }

        adapter.onBindViewHolder(holder, 0)
        advance(showDelay / 3)
        info.complete(imageInfo())
        advance(showDelay * 4)

        assertTrue(holder.binding.errorText.isVisible)
        assertFalse(everShown)
        assertFalse(holder.binding.progress.isVisible)
    }

    @Test
    fun anErrorHidesTheIndicator() {
        val gate = NeighborPreloadGate(first)
        val info = CompletableDeferred<ImageInfo>()
        val adapter = ImageViewerAdapter(activity, {}, gate) {
            info.await()
            throw IOException("offline")
        }.apply { replace(listOf(first)) }
        val holder = attachedHolderFor(adapter)

        adapter.onBindViewHolder(holder, 0)
        advance(showDelay * 2)
        assertTrue(holder.binding.progress.isVisible)
        info.complete(imageInfo())
        advance(1000)

        assertTrue(holder.binding.errorText.isVisible)
        assertFalse(holder.binding.progress.isVisible)
    }

    @Test
    fun recyclingHidesTheIndicatorAndCancelsAPendingOne() {
        val gate = NeighborPreloadGate(first)
        val adapter = ImageViewerAdapter(activity, {}, gate) {
            CompletableDeferred<ImageInfo>().await()
        }.apply { replace(listOf(first, second)) }
        val holder = attachedHolderFor(adapter)
        adapter.onBindViewHolder(holder, 0)
        advance(showDelay * 2)
        assertTrue(holder.binding.progress.isVisible)

        adapter.onViewRecycled(holder)
        advance(1000)
        assertFalse(holder.binding.progress.isVisible)

        // Swiped away before the delay ran out.
        gate.onPageSelected(second)
        adapter.onBindViewHolder(holder, 1)
        advance(showDelay / 2)
        adapter.onViewRecycled(holder)
        advance(showDelay * 4)
        assertFalse(holder.binding.progress.isVisible)
    }

    /**
     * A large image is decoded from its path. The view's default decoder would open our file
     * provider's URI, which for a remote file is a proxy file descriptor that can hang the system.
     */
    @Test
    fun aLargeImageIsDecodedFromItsPathNotAContentUri() {
        val gate = NeighborPreloadGate(first)
        val decoderPaths = mutableListOf<Path>()
        val opened = CountDownLatch(1)
        val adapter = ImageViewerAdapter(
            activity, {}, gate,
            newLargeImageDecoder = { path ->
                decoderPaths.add(path)
                PathImageRegionDecoder(path) {
                    opened.countDown()
                    ByteArrayInputStream(TestJpegs.encode(TestJpegs.quadrants(96, 64)))
                }
            }
        ) {
            // 61 MP like a large camera JPEG: too large for a bitmap, so the large view.
            ImageInfo(
                datedAttributes, 9504, 6336, "image/jpeg".asMimeType(),
                LargeImageOrientation.NORMAL
            )
        }.apply { replace(listOf(first)) }
        val holder = attachedHolderFor(adapter)

        adapter.onBindViewHolder(holder, 0)
        idle()

        assertTrue(holder.binding.largeImage.isVisible)
        // The view initializes its decoder on a background executor. Its own decoder would
        // have opened a content URI instead.
        assertTrue(opened.await(10, TimeUnit.SECONDS))
        assertEquals(1, decoderPaths.size)
        assertTrue(decoderPaths.single() === first)
        idle()
        assertFalse(holder.binding.errorText.isVisible)
    }

    /** B2: a neighbor that failed offline loads again when it is shown. */
    @Test
    fun aFailedPageLoadsAgainWhenItIsSelected() {
        val gate = NeighborPreloadGate(first)
        var online = false
        val adapter = ImageViewerAdapter(activity, {}, gate) {
            if (!online) {
                throw IOException("offline")
            }
            CompletableDeferred<ImageInfo>().await()
        }.apply { replace(listOf(first, second)) }
        val reloaded = mutableListOf<Int>()
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                assertTrue("reload without a payload", payload != null)
                repeat(itemCount) { reloaded += positionStart + it }
            }
        })
        val current = holderFor(adapter)
        val neighbor = holderFor(adapter)
        adapter.onBindViewHolder(current, 0)
        adapter.onBindViewHolder(neighbor, 1)
        idle()
        assertTrue(neighbor.binding.errorText.isVisible)

        // Back online, swiped to the neighbor: the page is bound again.
        online = true
        gate.onPageSelected(second)
        adapter.reloadIfFailed(1)
        assertEquals(listOf(1), reloaded)
        adapter.onBindViewHolder(neighbor, 1)
        idle()
        assertFalse(neighbor.binding.errorText.isVisible)

        // A page that did not fail is left alone, and a reload is only asked for once.
        adapter.reloadIfFailed(1)
        assertEquals(listOf(1), reloaded)
    }

    @Test
    fun aPageThatLoadedIsNotReloaded() {
        val gate = NeighborPreloadGate(first)
        val adapter = ImageViewerAdapter(activity, {}, gate) {
            CompletableDeferred<ImageInfo>().await()
        }.apply { replace(listOf(first, second)) }
        var changes = 0
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                changes++
            }
        })
        adapter.onBindViewHolder(holderFor(adapter), 0)
        idle()

        adapter.reloadIfFailed(0)
        adapter.reloadIfFailed(1)
        // Out of range, e.g. right after a delete.
        adapter.reloadIfFailed(5)

        assertEquals(0, changes)
    }
}
