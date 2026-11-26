/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TabBarScrollRangeTest {
    /** Dev #50: tabs could be pushed out of view although the bar was not full. */
    @Test
    fun aBarThatIsNotFullDoesNotScrollInLtr() {
        assertDoesNotScroll(View.LAYOUT_DIRECTION_LTR)
    }

    @Test
    fun aBarThatIsNotFullDoesNotScrollInRtl() {
        assertDoesNotScroll(View.LAYOUT_DIRECTION_RTL)
    }

    @Test
    fun aFullBarStillScrollsToItsLastTab() {
        val (tabLayout, plus) = layoutTabBar(View.LAYOUT_DIRECTION_LTR, tabCount = 10)
        assertTrue(tabLayout.canScrollHorizontally(1))

        tabLayout.scrollTabIntoView(9)
        ShadowLooper.idleMainLooper()

        val lastTab = tabLayout.getTabAt(9)!!.view
        val strip = tabLayout.getChildAt(0)
        val lastRight = tabLayout.left + strip.left + lastTab.right - tabLayout.scrollX
        assertTrue("last tab ends at $lastRight, plus at ${plus.left}", lastRight <= plus.left)
        assertFalse("bar scrolls past its last tab", tabLayout.canScrollHorizontally(1))
    }

    private fun assertDoesNotScroll(layoutDirection: Int) {
        val (tabLayout, _) = layoutTabBar(layoutDirection, tabCount = 2)
        val startScrollX = tabLayout.scrollX

        for (delta in listOf(1000, -1000)) {
            tabLayout.scrollBy(delta, 0)
            assertEquals("scrolled by $delta", startScrollX, tabLayout.scrollX)
        }
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
        root.layoutDirection = layoutDirection
        val tabLayout = root.findViewById<TabLayout>(R.id.tabLayout)
        val plus = root.findViewById<View>(R.id.addTabButton)
        for (index in 0 until tabCount) {
            val tab = tabLayout.newTab()
            val tabBinding =
                FileListTabBinding.inflate(LayoutInflater.from(activity), tabLayout, false)
            tabBinding.titleText.text = "tab$index"
            tabBinding.closeButton.isVisible = tabCount > 1
            tab.customView = tabBinding.root
            tabLayout.addTab(tab, index == 0)
        }
        val widthPx = (360 * activity.resources.displayMetrics.density).toInt()
        val heightPx = (800 * activity.resources.displayMetrics.density).toInt()
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
}
