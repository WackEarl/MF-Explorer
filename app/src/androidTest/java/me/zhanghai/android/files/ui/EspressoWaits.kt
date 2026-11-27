package me.zhanghai.android.files.ui

import android.view.View
import androidx.test.espresso.AmbiguousViewMatcherException
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.PerformException
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.swipeDown
import androidx.test.espresso.action.ViewActions.swipeUp
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import org.hamcrest.CoreMatchers.allOf
import org.hamcrest.Matcher
import org.hamcrest.StringDescription

/**
 * Wait for a view that appears after off-main-thread work (directory listing, file jobs, image
 * decode). Polls the UI thread instead of sleeping. The visibility check is part of the matcher
 * so a GONE sibling with the same id does not make the lookup ambiguous.
 */
fun waitUntilDisplayed(matcher: Matcher<View>, timeoutMs: Long = 15_000L) {
    waitUntil(allOf(matcher, isDisplayed()), matches(isDisplayed()), "displayed", timeoutMs)
}

fun waitUntilCompletelyDisplayed(matcher: Matcher<View>, timeoutMs: Long = 15_000L) {
    waitUntil(
        allOf(matcher, isCompletelyDisplayed()),
        matches(isCompletelyDisplayed()),
        "completely displayed",
        timeoutMs
    )
}

fun waitUntilCondition(
    description: String,
    timeoutMs: Long = 15_000L,
    condition: () -> Boolean
) {
    val deadline = System.currentTimeMillis() + timeoutMs
    var lastError: Throwable? = null
    while (System.currentTimeMillis() < deadline) {
        try {
            if (condition()) {
                return
            }
        } catch (error: Throwable) {
            rethrowIfAmbiguous(error)
            lastError = error
        }
        onView(isRoot()).perform(loopMainThreadForAtLeast(50))
    }
    throw PerformException.Builder()
        .withActionDescription("wait until $description")
        .withViewDescription("root")
        .withCause(lastError ?: AssertionError("condition was still false after $timeoutMs ms"))
        .build()
}

/**
 * Swipe a scrollable container until [item] is completely on screen. Used for drawer rows that
 * sit below the fold and for files created at the end of a long list. Alternates up and down
 * so a remembered scroll position or a reversed item order still reaches the row.
 */
fun scrollUntilCompletelyDisplayed(
    item: Matcher<View>,
    container: Matcher<View>,
    timeoutMs: Long = 15_000L
) {
    val deadline = System.currentTimeMillis() + timeoutMs
    var lastError: Throwable? = null
    var swipeDownNext = false
    while (System.currentTimeMillis() < deadline) {
        try {
            onView(allOf(item, isCompletelyDisplayed())).check(matches(isCompletelyDisplayed()))
            return
        } catch (error: Throwable) {
            rethrowIfAmbiguous(error)
            lastError = error
            try {
                onView(container).perform(if (swipeDownNext) swipeDown() else swipeUp())
            } catch (_: Throwable) {
                onView(isRoot()).perform(loopMainThreadForAtLeast(50))
            }
            swipeDownNext = !swipeDownNext
        }
    }
    val viewDescription = StringDescription().also { item.describeTo(it) }
    throw PerformException.Builder()
        .withActionDescription("scroll until completely displayed: $viewDescription")
        .withViewDescription("container")
        .withCause(lastError)
        .build()
}

private fun waitUntil(
    matcher: Matcher<View>,
    assertion: androidx.test.espresso.ViewAssertion,
    description: String,
    timeoutMs: Long
) {
    val deadline = System.currentTimeMillis() + timeoutMs
    var lastError: Throwable? = null
    while (System.currentTimeMillis() < deadline) {
        try {
            onView(matcher).check(assertion)
            return
        } catch (error: Throwable) {
            rethrowIfAmbiguous(error)
            lastError = error
            onView(isRoot()).perform(loopMainThreadForAtLeast(50))
        }
    }
    val viewDescription = StringDescription().also { matcher.describeTo(it) }
    throw PerformException.Builder()
        .withActionDescription("wait until $description: $viewDescription")
        .withViewDescription("root")
        .withCause(lastError)
        .build()
}

private fun rethrowIfAmbiguous(error: Throwable) {
    var current: Throwable? = error
    while (current != null) {
        if (current is AmbiguousViewMatcherException) {
            throw error
        }
        current = current.cause
    }
}

fun loopMainThreadForAtLeast(milliseconds: Long): ViewAction =
    object : ViewAction {
        override fun getConstraints(): Matcher<View> = isRoot()

        override fun getDescription(): String = "loop main thread for $milliseconds ms"

        override fun perform(uiController: UiController, view: View) {
            uiController.loopMainThreadUntilIdle()
            uiController.loopMainThreadForAtLeast(milliseconds)
        }
    }
