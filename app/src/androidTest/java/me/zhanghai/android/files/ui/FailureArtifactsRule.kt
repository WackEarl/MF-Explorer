package me.zhanghai.android.files.ui

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File

/**
 * Writes a screenshot and an activity / UI dump when a test fails. The emulator script also
 * takes a final screenshot after the Gradle run; that pair is taken after
 * [androidx.test.core.app.ActivityScenario] has already closed the app, so it usually shows
 * the launcher, not the failing screen.
 */
class FailureArtifactsRule : TestWatcher() {
    override fun failed(throwable: Throwable, description: Description) {
        val name = buildString {
            append(description.className.substringAfterLast('.'))
            append('_')
            append(description.methodName)
        }.replace(Regex("[^A-Za-z0-9._-]"), "_")
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
        val dumpFile = File(dir, "$name-ui.xml")
        writeShellOutput("uiautomator dump ${dumpFile.absolutePath}", File(dir, "$name-dump-log.txt"))
    }

    companion object {
        const val DIR_NAME = "ui-smoke-failures"

        fun outputDir(): File {
            val arguments = InstrumentationRegistry.getArguments()
            val additional = arguments.getString("additionalTestOutputDir")
            if (!additional.isNullOrEmpty()) {
                return File(additional)
            }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            return File(context.getExternalFilesDir(null), DIR_NAME)
        }
    }

    private fun writeShellOutput(command: String, file: File) {
        val pfd = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }
}
