package me.zhanghai.android.files

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import java.io.FileInputStream

/**
 * Instrumentation runner that grants all-files access (`MANAGE_EXTERNAL_STORAGE`) before any test
 * starts. `GrantPermissionRule` cannot grant this special permission, so the app-op is set through
 * the shell identity of UiAutomation. This keeps the all-files-access prompt from appearing.
 */
class StorageAccessRunner : AndroidJUnitRunner() {
    override fun onStart() {
        val packageName = targetContext.packageName
        grantShell("appops set $packageName MANAGE_EXTERNAL_STORAGE allow")
        grantShell("pm grant $packageName android.permission.POST_NOTIFICATIONS")
        super.onStart()
    }

    private fun grantShell(command: String) {
        val descriptor = uiAutomation.executeShellCommand(command)
        // Read to the end so that the command has finished before the tests begin.
        FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        descriptor.close()
    }
}
