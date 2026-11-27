package me.zhanghai.android.files

import android.os.Environment
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.ui.FailureArtifactsRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @get:Rule
    val failureArtifacts = FailureArtifactsRule()

    @Test
    fun allFilesAccessIsGranted() {
        assertTrue(
            "MANAGE_EXTERNAL_STORAGE was not granted by the runner",
            Environment.isExternalStorageManager()
        )
    }

    @Test
    fun mainActivityStartsAndResumes() {
        ActivityScenario.launch(FileListActivity::class.java).use { scenario ->
            assertTrue(scenario.state.isAtLeast(Lifecycle.State.RESUMED))
        }
    }
}
