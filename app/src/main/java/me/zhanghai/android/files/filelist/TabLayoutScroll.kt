/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import com.google.android.material.tabs.TabLayout

/**
 * Scrolls the tab bar just enough that the tab at [index] is fully inside the area between the
 * paddings, which keep tabs clear of the plus button. Rebuilding the tabs, e.g. after "+",
 * leaves the bar scrolled to the start, so the new tab was out of view (#67, rc2).
 */
internal fun TabLayout.scrollTabIntoView(index: Int) {
    val tab = getTabAt(index) ?: return
    val strip = getChildAt(0) ?: return
    val tabView = tab.view
    if (tabView.width == 0) {
        return
    }
    // Scroll coordinates are those of the strip, in both layout directions.
    val tabLeft = strip.left + tabView.left
    val tabRight = strip.left + tabView.right
    val visibleLeft = scrollX + paddingLeft
    val visibleRight = scrollX + width - paddingRight
    val target = when {
        tabRight - tabLeft > visibleRight - visibleLeft -> tabLeft - paddingLeft
        tabRight > visibleRight -> tabRight - width + paddingRight
        tabLeft < visibleLeft -> tabLeft - paddingLeft
        else -> return
    }
    scrollTo(target, 0)
}
