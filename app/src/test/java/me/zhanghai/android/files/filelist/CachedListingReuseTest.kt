/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.Success
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * rc3 finding B3: a folder listed while the network was still down kept showing "The NFS server
 * is unreachable" when it was opened again after the network was back, until an app restart.
 * The tab handed out its cached failed listing instead of listing the folder again.
 */
class CachedListingReuseTest {
    @Test
    fun failedListingIsListedAgain() {
        val failure = Failure<List<Any>>(null, IOException("The NFS server is unreachable"))
        assertFalse(isCachedListingReusable(failure))
    }

    @Test
    fun failureWithOldItemsIsListedAgain() {
        val failure = Failure(listOf<Any>("img-06.png"), IOException("Network is unreachable"))
        assertFalse(isCachedListingReusable(failure))
    }

    @Test
    fun loadedOrLoadingListingIsKept() {
        assertTrue(isCachedListingReusable(Success(listOf<Any>("img-01.jpg"))))
        assertTrue(isCachedListingReusable(Loading<List<Any>>(null)))
        assertTrue(isCachedListingReusable(null))
    }
}
