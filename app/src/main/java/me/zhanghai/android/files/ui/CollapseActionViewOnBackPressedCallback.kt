/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.appcompat.widget.Toolbar

/**
 * Collapses the expanded action view of [toolbar] on back, in place of the toolbar's own callback.
 *
 * On API 33+ a toolbar set as support action bar collapses its expanded action view from an
 * `OnBackInvokedCallback` with `PRIORITY_OVERLAY`. That runs before the input method's callback,
 * so the back gesture that should only hide the keyboard closes the search view and drops the
 * query. This callback goes through the `OnBackPressedDispatcher` instead, so the keyboard is
 * hidden first and the next back collapses the action view.
 *
 * After the action view is collapsed, the callback stays enabled while [hasActiveQuery] is true
 * so a further back can [clearQuery] and restore the unfiltered list. If it is enabled after a
 * configuration change before the menu is rebuilt, and there is no expanded action view and no
 * active query, the back press is re-dispatched instead of being swallowed.
 *
 * Must be created after `AppCompatActivity.setSupportActionBar()`, which enables the toolbar's
 * callback again. The owner keeps [isEnabled] in sync with whether an action view is expanded or
 * a query is still active.
 */
class CollapseActionViewOnBackPressedCallback(
    private val toolbar: Toolbar,
    private val dispatcher: OnBackPressedDispatcher,
    private val hasActiveQuery: () -> Boolean = { false },
    private val clearQuery: () -> Unit = {}
) : OnBackPressedCallback(toolbar.hasExpandedActionView()) {
    init {
        toolbar.isBackInvokedCallbackEnabled = false
    }

    override fun handleOnBackPressed() {
        if (toolbar.hasExpandedActionView()) {
            toolbar.collapseActionView()
            isEnabled = hasActiveQuery()
            return
        }
        if (hasActiveQuery()) {
            clearQuery()
            isEnabled = hasActiveQuery()
            return
        }
        isEnabled = false
        dispatcher.onBackPressed()
    }
}
