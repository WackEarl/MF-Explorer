/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.util.SparseArray
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.appbar.AppBarLayout
import me.zhanghai.android.files.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.xmlpull.v1.XmlPullParser

/**
 * The toolbar expanded again when the theme was switched: the coordinator layout around the app
 * bar had no ID, so it never saved the app bar's scroll state across the activity's recreation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppBarSavedStateTest {
    @Test
    fun theCoordinatorLayoutOfTheFileListHasAnId() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        val parser = activity.resources.getLayout(R.layout.file_list_fragment_include)
        var id: String? = null
        var found = false
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG &&
                parser.name == CoordinatorLayout::class.java.name) {
                found = true
                id = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "id")
                break
            }
        }
        assertEquals(true, found)
        assertNotNull("the coordinator layout needs an ID to save its state", id)
    }

    /** What the ID is for: with one, the app bar's offset is saved and restored. */
    @Test
    fun aCollapsedAppBarStaysCollapsedAfterRecreation() {
        val saved = SparseArray<Parcelable>()
        val collapsed = layoutAppBar(afterLayout = { appBar -> appBar.setExpanded(false, false) })
        collapsed.first.saveHierarchyState(saved)
        val collapsedTop = collapsed.second.top
        org.junit.Assert.assertTrue(collapsedTop < 0)

        val restored = layoutAppBar(beforeLayout = { root -> root.restoreHierarchyState(saved) })
        assertEquals(collapsedTop, restored.second.top)
    }

    private fun layoutAppBar(
        beforeLayout: (View) -> Unit = {},
        afterLayout: (AppBarLayout) -> Unit = {}
    ): Pair<View, AppBarLayout> {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_MaterialFiles)
        controller.setup()
        val coordinator = CoordinatorLayout(activity).apply { id = COORDINATOR_ID }
        LayoutInflater.from(activity)
            .inflate(R.layout.file_list_fragment_app_bar_include, coordinator, true)
        LayoutInflater.from(activity)
            .inflate(R.layout.file_list_fragment_content_include, coordinator, true)
        activity.setContentView(coordinator)
        beforeLayout(coordinator)
        val appBar = coordinator.findViewById<AppBarLayout>(R.id.appBarLayout)
        repeat(2) {
            coordinator.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
            )
            coordinator.layout(0, 0, 1080, 1920)
            ShadowLooper.idleMainLooper()
        }
        afterLayout(appBar)
        repeat(2) {
            coordinator.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
            )
            coordinator.layout(0, 0, 1080, 1920)
            ShadowLooper.idleMainLooper()
        }
        return coordinator to appBar
    }

    companion object {
        /** Like the file list's, which the first test checks. */
        private const val COORDINATOR_ID = 0x00c0ffee
    }
}
