package me.zhanghai.android.files.ui

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector

/**
 * Suppresses and dismisses system error / ANR dialogs that steal focus from Espresso
 * (typically "Pixel Launcher isn't responding" on the Pixel 6 emulator).
 */
object SystemDialogs {
    fun suppressAndDismiss() {
        shell("settings put global hide_error_dialogs 1")
        shell("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
        dismissNotRespondingDialog()
        shell("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
    }

    private fun dismissNotRespondingDialog() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        if (!isNotRespondingDialogVisible(device)) {
            return
        }
        // Prefer Wait so the launcher is not killed; fall back to Close app.
        for (label in listOf("Wait", "Close app", "Close", "OK")) {
            val button = device.findObject(UiSelector().text(label))
            if (button.waitForExists(500)) {
                try {
                    button.click()
                } catch (_: Throwable) {
                    // Try the next label.
                }
            }
            if (!isNotRespondingDialogVisible(device)) {
                return
            }
        }
        device.pressHome()
    }

    private fun isNotRespondingDialogVisible(device: UiDevice): Boolean {
        val selectors = listOf(
            UiSelector().textContains("isn't responding"),
            UiSelector().textContains("isnt responding"),
            UiSelector().textContains("Not responding")
        )
        return selectors.any { selector ->
            try {
                device.findObject(selector).exists()
            } catch (_: Throwable) {
                false
            }
        }
    }

    private fun shell(command: String) {
        val pfd = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
            input.copyTo(NullOutputStream)
        }
    }

    private object NullOutputStream : java.io.OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }
}
