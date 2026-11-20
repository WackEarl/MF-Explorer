/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import androidx.activity.OnBackPressedCallback
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
 * Must be created after `AppCompatActivity.setSupportActionBar()`, which enables the toolbar's
 * callback again. The owner keeps [isEnabled] in sync with whether an action view is expanded.
 */
class CollapseActionViewOnBackPressedCallback(
    private val toolbar: Toolbar
) : OnBackPressedCallback(toolbar.hasExpandedActionView()) {
    init {
        toolbar.isBackInvokedCallbackEnabled = false
    }

    override fun handleOnBackPressed() {
        toolbar.collapseActionView()
    }
}
