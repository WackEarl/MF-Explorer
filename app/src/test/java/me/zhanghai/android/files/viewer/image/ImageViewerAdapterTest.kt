/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.viewer.image

import android.os.Looper
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.view.isVisible
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import me.zhanghai.android.files.file.asMimeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.lang.reflect.Proxy

/** Issues #53 and #51 on a real view holder. */
@RunWith(RobolectricTestRunner::class)
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

    private val first = path("1.jpg")
    private val second = path("2.jpg")

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun holderFor(adapter: ImageViewerAdapter): ImageViewerAdapter.ViewHolder =
        adapter.onCreateViewHolder(FrameLayout(activity), 0)

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
}
