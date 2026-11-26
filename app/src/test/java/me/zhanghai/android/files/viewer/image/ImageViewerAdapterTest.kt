/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder
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
import org.robolectric.shadows.ShadowLog
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

    private val genericError: String
        get() = activity.getString(R.string.image_viewer_error)

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
        assertEquals(genericError, holder.binding.errorText.text)

        // A normal image would be handed to the image view if this result were still applied.
        firstInfo.complete(
            ImageInfo(attributes, 100, 100, "image/jpeg".asMimeType(), LargeImageOrientation.NORMAL)
        )
        idle()

        assertEquals(genericError, holder.binding.errorText.text)
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

        assertEquals(genericError, holder.binding.errorText.text)
        // The neighbor was let through after the current page failed, and failed the same way.
        assertEquals(genericError, neighbor.binding.errorText.text)
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

    /** A large image decoder that opens the file but cannot decode it, like HEIC on some devices. */
    private class UndecodableDecoder(
        private val width: Int,
        private val height: Int,
        val hang: CountDownLatch? = null
    ) : ImageRegionDecoder {
        @Volatile
        private var ready = false
        val decodeAttempts = java.util.concurrent.atomic.AtomicInteger()

        override fun init(context: Context, uri: Uri): Point {
            ready = true
            return Point(width, height)
        }

        override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
            decodeAttempts.incrementAndGet()
            hang?.await(30, TimeUnit.SECONDS)
            throw RuntimeException("Cannot decode region of r12-large.heic")
        }

        override fun isReady(): Boolean = ready

        override fun recycle() {
            ready = false
        }
    }

    /** Lays out and draws [view] until [done], as the view only decodes tiles when drawn. */
    private fun drawUntil(view: View, timeoutMillis: Long = 10_000, done: () -> Boolean) {
        val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!done() && System.currentTimeMillis() < deadline) {
            idle()
            view.measure(
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY)
            )
            view.layout(0, 0, 400, 300)
            view.draw(Canvas(bitmap))
            Thread.sleep(10)
        }
    }

    private fun largeImageInfo() =
        // 8000 × 6000 like r12-large.heic: too large for a bitmap, so the large view.
        ImageInfo(datedAttributes, 8000, 6000, "image/heic".asMimeType(), LargeImageOrientation.NORMAL)

    /** B4: a large image whose tiles cannot be decoded shows the error page, not a spinner. */
    @Test
    fun aLargeImageWhoseTilesCannotBeDecodedShowsTheErrorPage() {
        val gate = NeighborPreloadGate(first)
        val decoder = UndecodableDecoder(8000, 6000)
        val reads = mutableListOf<Path>()
        val adapter = ImageViewerAdapter(
            activity, {}, gate, newLargeImageDecoder = { decoder }
        ) { path ->
            reads.add(path)
            largeImageInfo()
        }.apply { replace(listOf(first, second)) }
        val holder = attachedHolderFor(adapter)
        val neighbor = holderFor(adapter)

        adapter.onBindViewHolder(holder, 0)
        adapter.onBindViewHolder(neighbor, 1)
        advance(showDelay * 2)
        assertTrue(holder.binding.progress.isVisible)
        drawUntil(holder.binding.root) { holder.binding.errorText.isVisible }

        assertTrue(decoder.decodeAttempts.get() > 0)
        assertTrue(holder.binding.errorText.isVisible)
        assertEquals(genericError, holder.binding.errorText.text)
        assertFalse(holder.binding.progress.isVisible)
        assertFalse(holder.binding.largeImage.isVisible)
        // The page counts as loaded, so the neighbor waiting for it is let through.
        idle()
        assertTrue(reads.any { it === second })
    }

    /** A decoder that never returns ends in the error page once the decode timeout ran out. */
    @Test
    fun aLargeImageDecoderThatHangsShowsTheErrorPageAfterTheTimeout() {
        val gate = NeighborPreloadGate(first)
        val hang = CountDownLatch(1)
        val decoder = UndecodableDecoder(8000, 6000, hang)
        // Generous, because the test clock may jump by several seconds while the view animates.
        val timeout = 60_000L
        val adapter = ImageViewerAdapter(
            activity, {}, gate, newLargeImageDecoder = { decoder },
            largeImageDecodeTimeoutMillis = timeout
        ) { largeImageInfo() }.apply { replace(listOf(first)) }
        val holder = attachedHolderFor(adapter)
        try {
            adapter.onBindViewHolder(holder, 0)
            drawUntil(holder.binding.root) { decoder.decodeAttempts.get() > 0 }
            assertTrue(decoder.decodeAttempts.get() > 0)
            advance(1000)
            advance(1000)
            assertFalse(holder.binding.errorText.isVisible)
            assertTrue(holder.binding.progress.isVisible)

            advance(timeout)

            assertTrue(holder.binding.errorText.isVisible)
            assertEquals(genericError, holder.binding.errorText.text)
            assertFalse(holder.binding.progress.isVisible)
            assertFalse(holder.binding.largeImage.isVisible)
        } finally {
            hang.countDown()
        }
    }

    /** Only decoding counts: a large image that is never drawn is not timed out. */
    @Test
    fun aLargeImageThatIsNotDrawnYetIsNotTimedOut() {
        val gate = NeighborPreloadGate(first)
        val decoder = UndecodableDecoder(8000, 6000)
        val timeout = 5_000L
        val adapter = ImageViewerAdapter(
            activity, {}, gate, newLargeImageDecoder = { decoder },
            largeImageDecodeTimeoutMillis = timeout
        ) { largeImageInfo() }.apply { replace(listOf(first)) }
        val holder = holderFor(adapter)

        adapter.onBindViewHolder(holder, 0)
        advance(timeout * 3)

        assertEquals(0, decoder.decodeAttempts.get())
        assertFalse(holder.binding.errorText.isVisible)
    }

    /**
     * B5: ViewPager2 selects a page from a scroll callback, where changing the adapter logs an
     * IllegalStateException. The reload of a failed page waits for the next frame.
     */
    @Test
    fun aFailedPageIsReloadedAfterTheScrollCallback() {
        val gate = NeighborPreloadGate(first)
        var online = false
        val adapter = ImageViewerAdapter(activity, {}, gate) {
            if (!online) {
                throw IOException("offline")
            }
            CompletableDeferred<ImageInfo>().await()
        }.apply { replace(listOf(first, second)) }
        val recyclerView = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
            this.adapter = adapter
        }
        activity.setContentView(recyclerView)
        recyclerView.measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY)
        )
        recyclerView.layout(0, 0, 400, 300)
        idle()
        val page = recyclerView.findViewHolderForAdapterPosition(0)
            as ImageViewerAdapter.ViewHolder
        assertTrue(page.binding.errorText.isVisible)

        online = true
        var changedInCallback = false
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                changedInCallback = changedInCallback || inScrollCallback
            }
        })
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                inScrollCallback = true
                adapter.reloadIfFailed(0)
                inScrollCallback = false
            }
        })
        ShadowLog.clear()
        // Part of a swipe, so the page stays laid out.
        recyclerView.scrollBy(50, 0)
        idle()

        assertFalse(changedInCallback)
        assertTrue(
            ShadowLog.getLogs().none { it.msg.orEmpty().contains("in a scroll callback") }
        )
        val reloaded = recyclerView.findViewHolderForAdapterPosition(0)
            as ImageViewerAdapter.ViewHolder
        assertFalse(reloaded.binding.errorText.isVisible)
    }

    private var inScrollCallback = false

    /** A large image decoder whose tiles are one color, and whose init can be held back. */
    private class ColorDecoder(
        private val color: Int,
        private val initGate: CountDownLatch? = null
    ) : ImageRegionDecoder {
        @Volatile
        private var ready = false
        val tiles = java.util.concurrent.atomic.AtomicInteger()
        val recycled = java.util.concurrent.atomic.AtomicBoolean()

        override fun init(context: Context, uri: Uri): Point {
            initGate?.await(30, TimeUnit.SECONDS)
            ready = true
            // Both like a 61 MP A7R V frame, so the view does not reset on a size change.
            return Point(9504, 6336)
        }

        override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
            tiles.incrementAndGet()
            return Bitmap.createBitmap(
                maxOf(1, sRect.width() / sampleSize), maxOf(1, sRect.height() / sampleSize),
                Bitmap.Config.ARGB_8888
            ).apply { eraseColor(color) }
        }

        override fun isReady(): Boolean = ready

        override fun recycle() {
            ready = false
            recycled.set(true)
        }
    }

    /**
     * B7: swiping fast through large images reuses a page's view while the decoder of its
     * previous image is still reading the file. That decoder must not end up in the view and
     * show the previous image on the new page.
     */
    @Test
    fun aDecoderOfThePreviousImageNeverShowsOnTheReboundPage() {
        val gate = NeighborPreloadGate(first)
        val previousInit = CountDownLatch(1)
        val previous = ColorDecoder(android.graphics.Color.RED, previousInit)
        val current = ColorDecoder(android.graphics.Color.GREEN)
        val adapter = ImageViewerAdapter(
            activity, {}, gate,
            newLargeImageDecoder = { path -> if (path === first) previous else current }
        ) {
            ImageInfo(
                datedAttributes, 9504, 6336, "image/jpeg".asMimeType(),
                LargeImageOrientation.NORMAL
            )
        }.apply { replace(listOf(first, second)) }
        val holder = attachedHolderFor(adapter)
        try {
            adapter.onBindViewHolder(holder, 0)
            idle()
            // Swiped on before the first image was read: the view is bound to the next one.
            gate.onPageSelected(second)
            adapter.onBindViewHolder(holder, 1)
            drawUntil(holder.binding.root) { current.tiles.get() > 0 }
            assertTrue(current.tiles.get() > 0)
            val currentTiles = current.tiles.get()

            // Now the read of the first image finishes.
            previousInit.countDown()
            drawUntil(holder.binding.root, timeoutMillis = 2_000) { previous.tiles.get() > 0 }
            idle()

            assertEquals(0, previous.tiles.get())
            assertTrue(previous.recycled.get())
            assertFalse(current.recycled.get())
            assertFalse(holder.binding.errorText.isVisible)
            assertTrue(current.tiles.get() >= currentTiles)
        } finally {
            previousInit.countDown()
        }
    }

    /** B7 as seen: the previous image's read finishes first, before the current one's. */
    @Test
    fun aDecoderOfThePreviousImageThatFinishesFirstIsNotShown() {
        val gate = NeighborPreloadGate(first)
        val previousInit = CountDownLatch(1)
        val currentInit = CountDownLatch(1)
        val previous = ColorDecoder(android.graphics.Color.RED, previousInit)
        val current = ColorDecoder(android.graphics.Color.GREEN, currentInit)
        val adapter = ImageViewerAdapter(
            activity, {}, gate,
            newLargeImageDecoder = { path -> if (path === first) previous else current }
        ) {
            ImageInfo(
                datedAttributes, 9504, 6336, "image/jpeg".asMimeType(),
                LargeImageOrientation.NORMAL
            )
        }.apply { replace(listOf(first, second)) }
        val holder = attachedHolderFor(adapter)
        try {
            adapter.onBindViewHolder(holder, 0)
            idle()
            gate.onPageSelected(second)
            adapter.onBindViewHolder(holder, 1)
            idle()

            previousInit.countDown()
            drawUntil(holder.binding.root, timeoutMillis = 2_000) { previous.tiles.get() > 0 }
            assertEquals(0, previous.tiles.get())
            assertTrue(holder.binding.progress.isVisible || !holder.binding.errorText.isVisible)

            currentInit.countDown()
            drawUntil(holder.binding.root) { current.tiles.get() > 0 }

            assertTrue(current.tiles.get() > 0)
            assertEquals(0, previous.tiles.get())
            assertTrue(previous.recycled.get())
            assertFalse(holder.binding.errorText.isVisible)
        } finally {
            previousInit.countDown()
            currentInit.countDown()
        }
    }
}
