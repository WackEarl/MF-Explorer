package me.zhanghai.android.files.ui

import android.app.Activity
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File

/**
 * Writes a screenshot and an activity / UI dump when a test fails. Capture happens while the
 * activity is still open: [useCapturing] takes the files before
 * [ActivityScenario.close], and [androidx.test.espresso.Espresso] failures that escape a
 * wait loop are handled the same way. [TestWatcher.failed] only fills in a missing capture;
 * by then [@After] and [ActivityScenario.close] may already have run, so the screen is often
 * the launcher. Files go to AGP's `additionalTestOutputDir` when the runner provides it
 * (`app/build/outputs/connected_android_test_additional_output/` on the host). The emulator
 * script also takes a final screenshot after the Gradle run; that pair is after close.
 */
class FailureArtifactsRule : TestWatcher() {
    private var captured = false
    private var testName = "unknown"

    override fun starting(description: Description) {
        captured = false
        testName = artifactName(description)
        current = this
    }

    override fun failed(throwable: Throwable, description: Description) {
        testName = artifactName(description)
        capture()
    }

    override fun finished(description: Description) {
        if (current === this) {
            current = null
        }
    }

    fun capture() {
        if (captured) {
            return
        }
        captured = true
        val name = testName
        val dir = outputDir()
        dir.mkdirs()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        try {
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            if (screenshot != null) {
                File(dir, "$name.png").outputStream().use { stream ->
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
                screenshot.recycle()
            }
        } catch (_: Throwable) {
            // Still try to write the dumps.
        }
        writeShellOutput("dumpsys activity activities", File(dir, "$name-activities.txt"))
        dumpUiHierarchy(File(dir, "$name-ui.xml"), File(dir, "$name-dump-log.txt"))
    }

    companion object {
        const val DIR_NAME = "ui-smoke-failures"

        @Volatile
        private var current: FailureArtifactsRule? = null

        fun captureCurrent() {
            current?.capture()
        }

        fun outputDir(): File {
            val arguments = InstrumentationRegistry.getArguments()
            val additional = arguments.getString("additionalTestOutputDir")
            if (!additional.isNullOrEmpty()) {
                return File(additional)
            }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            return File(context.getExternalFilesDir(null), DIR_NAME)
        }

        private fun artifactName(description: Description): String =
            buildString {
                append(description.className.substringAfterLast('.'))
                append('_')
                append(description.methodName)
            }.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    /**
     * Write the window hierarchy while the activity is still open. `uiautomator dump`
     * via the shell fails when the instrumentation already holds UiAutomation
     * (`UiAutomationService already registered`), so prefer [UiDevice.dumpWindowHierarchy]
     * in-process. Fall back to a device-side file, then `cat`.
     */
    private fun dumpUiHierarchy(local: File, logFile: File) {
        try {
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                .dumpWindowHierarchy(local)
            if (local.length() > 0L) {
                return
            }
        } catch (_: Throwable) {
            // Fall back to a shell dump.
        }
        val remote = "/sdcard/ui-smoke-dump.xml"
        writeShellOutput("rm -f $remote", logFile, append = true)
        writeShellOutput("uiautomator dump $remote", logFile, append = true)
        if (local.length() == 0L) {
            writeShellOutput("cat $remote", local)
        }
        if (local.length() == 0L) {
            // Last resort: some images return the dump on stdout.
            writeShellOutput("uiautomator dump /dev/tty", local)
        }
    }

    private fun writeShellOutput(command: String, file: File, append: Boolean = false) {
        val pfd = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
            val bytes = input.readBytes()
            if (append) {
                file.appendBytes(bytes)
            } else {
                file.writeBytes(bytes)
            }
        }
    }
}

/**
 * Like [ActivityScenario.use], but writes failure artifacts before [ActivityScenario.close]
 * so the screenshot still shows the failing screen.
 */
fun <A : Activity> ActivityScenario<A>.useCapturing(block: (ActivityScenario<A>) -> Unit) {
    try {
        block(this)
    } catch (error: Throwable) {
        FailureArtifactsRule.captureCurrent()
        throw error
    } finally {
        close()
    }
}
