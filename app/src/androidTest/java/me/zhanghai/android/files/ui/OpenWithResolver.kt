package me.zhanghai.android.files.ui

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/**
 * If opening a file shows a system disambiguation / "Open with" sheet, pick this app (or the
 * named activity label) and confirm "Just once". Returns as soon as [isDone] is true, including
 * when no chooser appears.
 */
fun resolveOpenWithIfPresent(
    isDone: () -> Boolean,
    vararg labels: String,
    timeoutMs: Long = 8_000L
) {
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    val deadline = System.currentTimeMillis() + timeoutMs
    var clickedLabel = false
    while (System.currentTimeMillis() < deadline) {
        if (isDone()) {
            return
        }
        if (!clickedLabel) {
            val target = firstObject(device, labels.map { By.text(it) })
            if (target != null) {
                target.click()
                clickedLabel = true
                device.waitForIdle()
                continue
            }
        }
        val justOnce = firstObject(
            device,
            listOf(
                By.text("Just once"),
                By.text("Just Once"),
                By.res("android:id/button_once")
            )
        )
        if (justOnce != null) {
            justOnce.click()
            device.waitForIdle()
        }
        device.wait(Until.gone(By.res("android:id/button_once")), 200)
    }
}

private fun firstObject(device: UiDevice, selectors: List<BySelector>): UiObject2? {
    for (selector in selectors) {
        val obj = device.findObject(selector) ?: continue
        if (obj.isEnabled) {
            return obj
        }
    }
    return null
}
