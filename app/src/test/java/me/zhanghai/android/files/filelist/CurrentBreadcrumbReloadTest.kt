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
 * 2.1.7 finding: tapping the current breadcrumb segment of a folder in the error state only
 * scrolled the breadcrumb, so the folder stayed in "Error" after the network was back.
 */
class CurrentBreadcrumbReloadTest {
    @Test
    fun failedFolderIsReloaded() {
        val failure = Failure<List<Any>>(null, IOException("The NFS server is unreachable"))
        assertTrue(shouldReloadOnCurrentBreadcrumbClick(failure))
    }

    @Test
    fun failedFolderWithOldItemsIsReloaded() {
        val failure = Failure(listOf<Any>("img-06.png"), IOException("Network is unreachable"))
        assertTrue(shouldReloadOnCurrentBreadcrumbClick(failure))
    }

    @Test
    fun loadedOrLoadingFolderIsNotReloaded() {
        assertFalse(shouldReloadOnCurrentBreadcrumbClick(Success(listOf<Any>("img-01.jpg"))))
        assertFalse(shouldReloadOnCurrentBreadcrumbClick(Loading<List<Any>>(null)))
        assertFalse(shouldReloadOnCurrentBreadcrumbClick(null))
    }
}
