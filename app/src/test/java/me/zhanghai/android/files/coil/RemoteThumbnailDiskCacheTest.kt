/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.coil

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import org.robolectric.RuntimeEnvironment
import coil.ImageLoader
import coil.decode.DataSource
import coil.disk.DiskCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.Options
import coil.request.SuccessResult
import coil.size.Size
import java8.nio.file.Path
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.attribute.FileTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoteThumbnailDiskCacheTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var directory: File
    private lateinit var diskCache: DiskCache

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("thumbnail-cache").toFile()
        diskCache = DiskCache.Builder().directory(directory).maxSizeBytes(10L * 1024 * 1024)
            .build()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    /** A path that is neither Linux nor document, so it counts as remote. Any file access fails. */
    private fun remotePath(name: String): Path =
        Proxy.newProxyInstance(
            Path::class.java.classLoader, arrayOf(Path::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "toString" -> "nfs://192.0.2.1/export/$name"
                "hashCode" -> name.hashCode()
                "equals" -> proxy === args?.get(0)
                else -> throw AssertionError("Remote file touched: ${method.name}")
            }
        } as Path

    private fun attributes(modifiedMillis: Long, size: Long): BasicFileAttributes =
        Proxy.newProxyInstance(
            BasicFileAttributes::class.java.classLoader, arrayOf(BasicFileAttributes::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "lastModifiedTime" -> FileTime.from(modifiedMillis, TimeUnit.MILLISECONDS)
                "size" -> size
                "toString" -> "attributes"
                else -> throw UnsupportedOperationException(method.name)
            }
        } as BasicFileAttributes

    private fun bitmap(): Bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)

    private fun successResult(
        data: Any,
        dataSource: DataSource = DataSource.NETWORK,
        diskCacheKey: String? = null,
        diskCachePolicy: CachePolicy = CachePolicy.ENABLED
    ): SuccessResult {
        val request = ImageRequest.Builder(context)
            .data(data)
            .diskCachePolicy(diskCachePolicy)
            .build()
        return SuccessResult(
            BitmapDrawable(context.resources, bitmap()), request, dataSource,
            diskCacheKey = diskCacheKey
        )
    }

    @Test
    fun thumbnailSizeOnlyForSmallPixelSizes() {
        assertEquals(200 to 150, RemoteThumbnailDiskCache.thumbnailSize(Size(200, 150)))
        assertEquals(512 to 384, RemoteThumbnailDiskCache.thumbnailSize(Size(512, 384)))
        assertNull(RemoteThumbnailDiskCache.thumbnailSize(Size(513, 384)))
        assertNull(RemoteThumbnailDiskCache.thumbnailSize(Size(512, 385)))
        assertNull(RemoteThumbnailDiskCache.thumbnailSize(Size.ORIGINAL))
    }

    @Test
    fun keyChangesWithFileAndSize() {
        val key = RemoteThumbnailDiskCache.key("nfs://h/e/a.jpg", 1000L, 10L, 200, 150)
        assertEquals(key, RemoteThumbnailDiskCache.key("nfs://h/e/a.jpg", 1000L, 10L, 200, 150))
        assertNotEquals(key, RemoteThumbnailDiskCache.key("nfs://h/e/b.jpg", 1000L, 10L, 200, 150))
        assertNotEquals(key, RemoteThumbnailDiskCache.key("nfs://h/e/a.jpg", 2000L, 10L, 200, 150))
        assertNotEquals(key, RemoteThumbnailDiskCache.key("nfs://h/e/a.jpg", 1000L, 11L, 200, 150))
        assertNotEquals(key, RemoteThumbnailDiskCache.key("nfs://h/e/a.jpg", 1000L, 10L, 400, 300))
    }

    @Test
    fun writtenThumbnailIsReadBack() {
        val key = RemoteThumbnailDiskCache.key("nfs://h/e/a.jpg", 1000L, 10L, 200, 150)
        assertNull(RemoteThumbnailDiskCache.read(diskCache, key))
        assertTrue(RemoteThumbnailDiskCache.write(diskCache, key, bitmap()))
        val result = RemoteThumbnailDiskCache.read(diskCache, key)
        assertNotNull(result)
        result!!.source.use { source ->
            assertEquals(DataSource.DISK, result.dataSource)
            assertEquals(RemoteThumbnailDiskCache.MIME_TYPE, result.mimeType)
            assertTrue(source.source().readByteArray().isNotEmpty())
        }
    }

    @Test
    fun freshRemoteThumbnailIsWritten() {
        val path = remotePath("a.jpg")
        val result = successResult(path to attributes(1000L, 10L))
        val entry = RemoteThumbnailDiskCache.entryToWrite(result, Size(200, 150))
        assertNotNull(entry)
        assertEquals(
            RemoteThumbnailDiskCache.key(path.toString(), 1000L, 10L, 200, 150), entry!!.first
        )
    }

    @Test
    fun cachedOrLargeOrDisabledResultIsNotWritten() {
        val data = remotePath("a.jpg") to attributes(1000L, 10L)
        val size = Size(200, 150)
        assertNull(RemoteThumbnailDiskCache.entryToWrite(
            successResult(data, dataSource = DataSource.MEMORY_CACHE), size
        ))
        assertNull(RemoteThumbnailDiskCache.entryToWrite(
            successResult(data, dataSource = DataSource.DISK, diskCacheKey = "k"), size
        ))
        assertNull(RemoteThumbnailDiskCache.entryToWrite(
            successResult(data, diskCachePolicy = CachePolicy.DISABLED), size
        ))
        assertNull(RemoteThumbnailDiskCache.entryToWrite(successResult(data), Size(1024, 768)))
        assertNull(RemoteThumbnailDiskCache.entryToWrite(successResult("not a path"), size))
    }

    /** What the fetcher asks before it reads a remote file. Before the fix nothing was stored. */
    @Test
    fun storedThumbnailIsFoundForTheSameFileAndSize() {
        val path = remotePath("a.jpg")
        val attributes = attributes(1000L, 10L)
        val imageLoader = ImageLoader.Builder(context).diskCache(diskCache).build()
        val options = Options(context, size = Size(200, 150))
        assertNull(RemoteThumbnailDiskCache.readStored(path, attributes, 200, 150, options, imageLoader))
        val key = RemoteThumbnailDiskCache.key(path, attributes, 200, 150)
        assertTrue(RemoteThumbnailDiskCache.write(diskCache, key, bitmap()))
        val result = RemoteThumbnailDiskCache.readStored(
            path, attributes, 200, 150, options, imageLoader
        )
        assertNotNull(result)
        result!!.source.close()
        assertEquals(DataSource.DISK, result.dataSource)
        // A changed file or another cell size misses.
        assertNull(RemoteThumbnailDiskCache.readStored(
            path, attributes(2000L, 10L), 200, 150, options, imageLoader
        ))
        assertNull(RemoteThumbnailDiskCache.readStored(
            path, attributes, 400, 300, options, imageLoader
        ))
        // The setting turns the read off through the request's disk cache policy.
        val disabled = Options(
            context, size = Size(200, 150), diskCachePolicy = CachePolicy.DISABLED
        )
        assertNull(RemoteThumbnailDiskCache.readStored(
            path, attributes, 200, 150, disabled, imageLoader
        ))
    }
}
