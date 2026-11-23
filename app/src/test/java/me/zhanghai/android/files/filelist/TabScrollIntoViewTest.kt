/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
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

/** #67 in rc2: after "+" the tab bar jumped to the start and the new tab was not in view. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TabScrollIntoViewTest {
    @Test
    fun newLastTabIsScrolledIntoViewInLtr() {
        val (tabLayout, plus) = layoutTabBar(View.LAYOUT_DIRECTION_LTR, tabCount = 10)
        resetScroll(tabLayout)
        assertFalse("precondition: last tab starts out of view", isFullyVisible(tabLayout, 9))

        tabLayout.scrollTabIntoView(9)
        ShadowLooper.idleMainLooper()

        assertTrue("last tab must be fully visible", isFullyVisible(tabLayout, 9))
        assertFalse(
            "last tab must not be under the plus button",
            Rect.intersects(bounds(tabLayout.getTabAt(9)!!.view), bounds(plus))
        )
    }

    @Test
    fun newLastTabIsScrolledIntoViewInRtl() {
        val (tabLayout, plus) = layoutTabBar(View.LAYOUT_DIRECTION_RTL, tabCount = 10)
        resetScroll(tabLayout)
        assertFalse("precondition: last tab starts out of view", isFullyVisible(tabLayout, 9))

        tabLayout.scrollTabIntoView(9)
        ShadowLooper.idleMainLooper()

        assertTrue("last tab must be fully visible", isFullyVisible(tabLayout, 9))
        assertFalse(
            "last tab must not be under the plus button",
            Rect.intersects(bounds(tabLayout.getTabAt(9)!!.view), bounds(plus))
        )
    }

    @Test
    fun firstTabIsScrolledBackIntoView() {
        val (tabLayout, _) = layoutTabBar(View.LAYOUT_DIRECTION_LTR, tabCount = 10)
        tabLayout.scrollTo(Int.MAX_VALUE / 2, 0)
        ShadowLooper.idleMainLooper()
        assertFalse(isFullyVisible(tabLayout, 0))

        tabLayout.scrollTabIntoView(0)
        ShadowLooper.idleMainLooper()

        assertTrue(isFullyVisible(tabLayout, 0))
    }

    @Test
    fun visibleTabDoesNotMoveTheBar() {
        val (tabLayout, _) = layoutTabBar(View.LAYOUT_DIRECTION_LTR, tabCount = 10)
        tabLayout.scrollTo(0, 0)
        ShadowLooper.idleMainLooper()
        assertTrue(isFullyVisible(tabLayout, 0))

        tabLayout.scrollTabIntoView(0)

        assertEquals(0, tabLayout.scrollX)
    }

    private fun resetScroll(tabLayout: TabLayout) {
        // What a rebuild leaves behind: the start of the bar in view.
        if (tabLayout.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            tabLayout.scrollTo(Int.MAX_VALUE / 2, 0)
        } else {
            tabLayout.scrollTo(0, 0)
        }
        ShadowLooper.idleMainLooper()
    }

    private fun isFullyVisible(tabLayout: TabLayout, index: Int): Boolean {
        val content = bounds(tabLayout)
        content.left += tabLayout.paddingLeft
        content.right -= tabLayout.paddingRight
        return content.contains(bounds(tabLayout.getTabAt(index)!!.view))
    }

    private fun layoutTabBar(layoutDirection: Int, tabCount: Int): Pair<TabLayout, View> {
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
        val plus = root.findViewById<View>(R.id.addTabButton)
        root.layoutDirection = layoutDirection
        tabBar.layoutDirection = layoutDirection
        tabLayout.layoutDirection = layoutDirection
        for (index in 0 until tabCount) {
            val tab = tabLayout.newTab()
            val binding = FileListTabBinding.inflate(LayoutInflater.from(activity), tabLayout, false)
            binding.titleText.text = "tab$index"
            binding.closeButton.isVisible = true
            tab.customView = binding.root
            tabLayout.addTab(tab, index == tabCount - 1)
        }
        val density = activity.resources.displayMetrics.density
        val widthPx = (360 * density).toInt()
        val heightPx = (800 * density).toInt()
        repeat(2) {
            root.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
            )
            root.layout(0, 0, widthPx, heightPx)
            ShadowLooper.idleMainLooper()
        }
        return tabLayout to plus
    }

    private fun bounds(view: View): Rect {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }
}
