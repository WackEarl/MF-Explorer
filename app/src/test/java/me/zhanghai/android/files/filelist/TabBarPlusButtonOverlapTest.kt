/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.tabs.TabLayout
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.FileListTabBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TabBarPlusButtonOverlapTest {
    @Test
    fun lastTabStaysVisibleBesidePlusButtonInLtr() {
        assertLastTabFullyVisibleBesidePlusButton(
            widthDp = 360,
            layoutDirection = View.LAYOUT_DIRECTION_LTR,
            tabCount = 8
        )
    }

    @Test
    fun lastTabStaysVisibleBesidePlusButtonInRtl() {
        assertLastTabFullyVisibleBesidePlusButton(
            widthDp = 360,
            layoutDirection = View.LAYOUT_DIRECTION_RTL,
            tabCount = 8
        )
    }

    @Test
    fun lastTabStaysVisibleBesidePlusButtonOnNarrowWidth() {
        assertLastTabFullyVisibleBesidePlusButton(
            widthDp = 240,
            layoutDirection = View.LAYOUT_DIRECTION_LTR,
            tabCount = 8
        )
    }

    private fun assertLastTabFullyVisibleBesidePlusButton(
        widthDp: Int,
        layoutDirection: Int,
        tabCount: Int
    ) {
        val host = layoutTabBar(widthDp, layoutDirection, tabCount)
        scrollTabsToEnd(host)

        val lastTab = host.tabLayout.getTabAt(tabCount - 1)!!
        val lastTabView = lastTab.view
        val tabBinding = FileListTabBinding.bind(lastTab.customView!!)
        val plus = host.addTabButton
        val lastBounds = boundsOnScreen(lastTabView)
        val titleBounds = boundsOnScreen(tabBinding.titleText)
        val closeBounds = boundsOnScreen(tabBinding.closeButton)
        val plusBounds = boundsOnScreen(plus)
        val contentBounds = tabContentBoundsOnScreen(host.tabLayout)

        assertEquals(
            "tab layout end inset must reserve the plus button width",
            plus.width,
            tabEndPadding(host.tabLayout, layoutDirection)
        )
        assertTrue(
            "last tab has inverted or empty bounds: $lastBounds",
            lastBounds.width() > 0 && lastBounds.left < lastBounds.right
        )
        assertTrue(
            "tab title has inverted or empty bounds: $titleBounds",
            titleBounds.width() > 0 && titleBounds.left < titleBounds.right
        )
        assertTrue("close button should be visible", tabBinding.closeButton.isVisible)
        assertTrue(
            "close button has inverted or empty bounds: $closeBounds",
            closeBounds.width() > 0 && closeBounds.left < closeBounds.right
        )
        assertFalse(
            "plus button overlaps last tab $lastBounds vs $plusBounds",
            Rect.intersects(lastBounds, plusBounds)
        )
        assertFalse(
            "plus button overlaps tab title $titleBounds vs $plusBounds",
            Rect.intersects(titleBounds, plusBounds)
        )
        assertFalse(
            "plus button overlaps close button $closeBounds vs $plusBounds",
            Rect.intersects(closeBounds, plusBounds)
        )
        assertFalse(
            "plus button overlaps tab content area $contentBounds vs $plusBounds",
            Rect.intersects(contentBounds, plusBounds)
        )
        assertTrue(
            "last tab $lastBounds is not fully inside tab content $contentBounds",
            contentBounds.contains(lastBounds)
        )
        assertTrue(
            "close button $closeBounds is not fully inside tab content $contentBounds",
            contentBounds.contains(closeBounds)
        )
        assertTrue("tab layout should clip to its end inset", host.tabLayout.clipToPadding)
        assertTrue("tab bar should clip children", (host.tabBar as ViewGroup).clipChildren)
    }

    private data class Host(
        val tabBar: View,
        val tabLayout: TabLayout,
        val addTabButton: View
    )

    private fun layoutTabBar(widthDp: Int, layoutDirection: Int, tabCount: Int): Host {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_MaterialFiles)
        controller.setup()

        val root = FrameLayout(activity)
        LayoutInflater.from(activity)
            .inflate(R.layout.file_list_fragment_app_bar_include, root, true)
        activity.setContentView(root)

        val tabBar = root.findViewById<View>(R.id.tabBarLayout)
        val tabLayout = root.findViewById<TabLayout>(R.id.tabLayout)
        val addTabButton = root.findViewById<View>(R.id.addTabButton)
        root.layoutDirection = layoutDirection
        tabBar.layoutDirection = layoutDirection
        tabLayout.layoutDirection = layoutDirection

        for (index in 0 until tabCount) {
            val tab = tabLayout.newTab()
            val customView = FileListTabBinding.inflate(
                LayoutInflater.from(activity),
                tabLayout,
                false
            )
            customView.titleText.text = if (index == tabCount - 1) "Download" else "tab$index"
            customView.closeButton.isVisible = true
            tab.customView = customView.root
            tabLayout.addTab(tab, index == tabCount - 1)
        }

        val widthPx = (widthDp * activity.resources.displayMetrics.density).toInt()
        val heightPx = (800 * activity.resources.displayMetrics.density).toInt()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, widthPx, heightPx)
        ShadowLooper.idleMainLooper()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, widthPx, heightPx)
        ShadowLooper.idleMainLooper()
        return Host(tabBar, tabLayout, addTabButton)
    }

    private fun scrollTabsToEnd(host: Host) {
        val tabLayout = host.tabLayout
        val lastIndex = tabLayout.tabCount - 1
        tabLayout.getTabAt(lastIndex)?.select()
        ShadowLooper.idleMainLooper()
        val strip = tabLayout.getChildAt(0) ?: return
        val visibleWidth = tabLayout.width - tabLayout.paddingLeft - tabLayout.paddingRight
        val maxScroll = (strip.width - visibleWidth).coerceAtLeast(0)
        if (tabLayout.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            tabLayout.scrollTo(0, 0)
        } else {
            tabLayout.scrollTo(maxScroll, 0)
        }
        ShadowLooper.idleMainLooper()
        tabLayout.getTabAt(lastIndex)?.select()
        ShadowLooper.idleMainLooper()
        if (tabLayout.layoutDirection == View.LAYOUT_DIRECTION_LTR) {
            tabLayout.scrollTo(maxScroll, 0)
        }
        ShadowLooper.idleMainLooper()
    }

    private fun tabEndPadding(tabLayout: TabLayout, layoutDirection: Int): Int {
        return if (layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            tabLayout.paddingLeft
        } else {
            tabLayout.paddingRight
        }
    }

    private fun tabContentBoundsOnScreen(tabLayout: TabLayout): Rect {
        val bounds = boundsOnScreen(tabLayout)
        bounds.left += tabLayout.paddingLeft
        bounds.right -= tabLayout.paddingRight
        return bounds
    }

    private fun boundsOnScreen(view: View): Rect {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
    }
}
