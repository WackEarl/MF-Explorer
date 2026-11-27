/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import android.animation.Animator
import android.animation.ValueAnimator
import android.os.Looper
import android.view.View
import android.view.ViewPropertyAnimator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.isVisible
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.TimeUnit

/**
 * The viewer's error page is faded out when a new load starts and faded in when the load
 * fails. A fadeOut whose end is delivered after the newer fadeIn must not hide the page.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ViewFadeTest {
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    private fun laidOutView(initialVisibility: Int, alpha: Float): TextView {
        val view = TextView(activity).apply {
            text = "Cannot show this image"
            visibility = initialVisibility
            this.alpha = alpha
        }
        val parent = FrameLayout(activity)
        parent.addView(view, FrameLayout.LayoutParams(200, 100))
        activity.setContentView(parent)
        parent.measure(
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        )
        parent.layout(0, 0, 200, 100)
        shadowOf(Looper.getMainLooper()).idle()
        return view
    }

    private fun idlePastAnimation() {
        shadowOf(Looper.getMainLooper()).idleFor(
            activity.shortAnimTime.toLong() * 3,
            TimeUnit.MILLISECONDS
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * On a real device a duration-0 fadeOut posts [Animator.AnimatorListener.onAnimationEnd]
     * after [loadImage][me.zhanghai.android.files.viewer.image] has already called showError().
     * Robolectric often runs that end synchronously, so this test delivers the stale success
     * itself after a newer fadeIn.
     */
    @Test
    fun aStaleFadeOutCompletionDoesNotHideTheViewAfterANewerFadeIn() {
        val view = laidOutView(View.VISIBLE, 1f)
        assertTrue(view.isLaidOut)

        view.fadeOutUnsafe()
        val staleEnd = view.animate().requireListener()
        // Detach so the stale success is not turned into a cancel by fadeIn's start().
        view.animate().setListener(null)

        view.fadeInUnsafe(true)
        staleEnd.onAnimationEnd(ValueAnimator.ofFloat(0f, 1f))

        idlePastAnimation()

        assertTrue(
            "a stale fadeOut ended after fadeIn and hid the view " +
                "(visible=${view.isVisible}, visibility=${view.visibility}, alpha=${view.alpha})",
            view.isVisible
        )
        assertFalse("the view was faded to transparent", view.alpha == 0f)
    }

    @Test
    fun aCompletedFadeOutStillHidesTheView() {
        val view = laidOutView(View.VISIBLE, 1f)
        assertTrue(view.isLaidOut)

        view.fadeOutUnsafe()
        idlePastAnimation()

        assertFalse(
            "a finished fadeOut left the view visible " +
                "(visible=${view.isVisible}, visibility=${view.visibility}, alpha=${view.alpha})",
            view.isVisible
        )
    }

    @Test
    fun aFadeOutThatEndsAfterANewerFadeInDoesNotHideAVisibleView() {
        val view = laidOutView(View.VISIBLE, 1f)
        assertTrue(view.isLaidOut)

        view.fadeOutUnsafe()
        view.fadeInUnsafe(true)

        idlePastAnimation()

        assertTrue(
            "a stale fadeOut ended after fadeIn and hid the view " +
                "(visible=${view.isVisible}, visibility=${view.visibility}, alpha=${view.alpha})",
            view.isVisible
        )
        assertFalse("the view was faded to transparent", view.alpha == 0f)
    }
}

private fun ViewPropertyAnimator.requireListener(): Animator.AnimatorListener {
    var type: Class<*>? = javaClass
    while (type != null) {
        try {
            val field = type.getDeclaredField("mListener")
            field.isAccessible = true
            return field.get(this) as Animator.AnimatorListener
        } catch (_: NoSuchFieldException) {
            type = type.superclass
        }
    }
    error("ViewPropertyAnimator.mListener is missing")
}
