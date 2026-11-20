/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.MenuProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CollapseActionViewOnBackPressedCallbackTest {
    private lateinit var activity: AppCompatActivity
    private lateinit var toolbar: Toolbar
    private lateinit var searchItem: MenuItem
    private lateinit var searchView: SearchView

    @Before
    fun setUp() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        activity = controller.get()
        activity.setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        controller.create()
        toolbar = Toolbar(activity)
        activity.setContentView(toolbar)
        activity.setSupportActionBar(toolbar)
        activity.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                searchView = SearchView(activity)
                searchItem = menu.add("Search").apply {
                    actionView = searchView
                    setShowAsAction(
                        MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW
                    )
                }
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean = false
        })
        controller.start().resume().visible()
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun replacesToolbarBackInvokedCallback() {
        // setSupportActionBar() lets the toolbar collapse action views ahead of the keyboard.
        assertTrue(toolbar.isBackInvokedCallbackEnabled)
        CollapseActionViewOnBackPressedCallback(toolbar, activity.onBackPressedDispatcher)
        assertFalse(toolbar.isBackInvokedCallbackEnabled)
    }

    @Test
    fun keepsQueryWhenSearchViewLosesFocus() {
        val callback = CollapseActionViewOnBackPressedCallback(
            toolbar,
            activity.onBackPressedDispatcher
        )
        activity.onBackPressedDispatcher.addCallback(callback)
        searchItem.expandActionView()
        callback.isEnabled = true
        searchView.setQuery("report", false)

        searchView.clearFocus()
        ShadowLooper.idleMainLooper()

        assertTrue(searchItem.isActionViewExpanded)
        assertEquals("report", searchView.query.toString())
    }

    @Test
    fun collapsesSearchViewOnBackButKeepsCallbackWhenQueryIsActive() {
        var active = true
        var cleared = false
        val callback = CollapseActionViewOnBackPressedCallback(
            toolbar,
            activity.onBackPressedDispatcher,
            hasActiveQuery = { active },
            clearQuery = { cleared = true; active = false }
        )
        activity.onBackPressedDispatcher.addCallback(callback)
        searchItem.expandActionView()
        callback.isEnabled = true
        searchView.setQuery("report", false)

        activity.onBackPressedDispatcher.onBackPressed()

        assertFalse(searchItem.isActionViewExpanded)
        assertTrue(callback.isEnabled)
        assertFalse(cleared)
    }

    @Test
    fun clearsQueryOnBackAfterSearchViewIsCollapsed() {
        var active = true
        var cleared = false
        val callback = CollapseActionViewOnBackPressedCallback(
            toolbar,
            activity.onBackPressedDispatcher,
            hasActiveQuery = { active },
            clearQuery = { cleared = true; active = false }
        )
        activity.onBackPressedDispatcher.addCallback(callback)
        searchItem.expandActionView()
        callback.isEnabled = true
        searchView.setQuery("report", false)

        activity.onBackPressedDispatcher.onBackPressed()
        activity.onBackPressedDispatcher.onBackPressed()

        assertTrue(cleared)
        assertFalse(callback.isEnabled)
    }

    @Test
    fun redispatchesBackWhenEnabledWithoutExpandedActionView() {
        var lowerHandled = false
        val lower = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                lowerHandled = true
            }
        }
        activity.onBackPressedDispatcher.addCallback(lower)
        val callback = CollapseActionViewOnBackPressedCallback(
            toolbar,
            activity.onBackPressedDispatcher
        )
        activity.onBackPressedDispatcher.addCallback(callback)
        callback.isEnabled = true
        assertFalse(toolbar.hasExpandedActionView())

        activity.onBackPressedDispatcher.onBackPressed()

        assertTrue(lowerHandled)
        assertFalse(callback.isEnabled)
    }
}
