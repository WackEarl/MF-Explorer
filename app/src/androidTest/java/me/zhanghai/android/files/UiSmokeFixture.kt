package me.zhanghai.android.files

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Environment
import java8.nio.file.Paths
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.settings.Settings
import java.io.File

/**
 * Isolated local directory for UI smoke tests. No network paths, no keystores, no production
 * sample images.
 */
object UiSmokeFixture {
    const val ROOT_NAME = "MFExplorerUiSmoke"
    const val IMAGE_NAME = "zzz-smoke.png"
    const val FIRST_FOLDER = "aaa-00"
    const val NEW_FOLDER = "zzz-ui-smoke"
    const val PAD_COUNT = 24

    fun rootDir(): File = File(Environment.getExternalStorageDirectory(), ROOT_NAME)

    fun imageFile(): File = File(rootDir(), IMAGE_NAME)

    fun reset() {
        Settings.FILE_LIST_OPEN_TABS.putValue(null)
        Settings.FILE_LIST_ANIMATION.putValue(false)
        val root = rootDir()
        if (root.exists()) {
            root.deleteRecursively()
        }
        check(root.mkdirs()) { "Could not create ${root.absolutePath}" }
        for (index in 0 until PAD_COUNT) {
            val folder = File(root, "aaa-%02d".format(index))
            check(folder.mkdir()) { "Could not create ${folder.absolutePath}" }
        }
        writeSyntheticImage(imageFile())
    }

    fun viewListIntent(): Intent =
        FileListActivity.createViewIntent(Paths.get(rootDir().absolutePath))

    private fun writeSyntheticImage(file: File) {
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(46, 125, 50))
        file.outputStream().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                "Could not write ${file.absolutePath}"
            }
        }
        bitmap.recycle()
    }
}
