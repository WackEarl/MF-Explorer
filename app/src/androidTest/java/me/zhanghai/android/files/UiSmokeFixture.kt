package me.zhanghai.android.files

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import java8.nio.file.Paths
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.filelist.FileSortOptions
import me.zhanghai.android.files.filelist.FileViewType
import me.zhanghai.android.files.filelist.OpenTabs
import me.zhanghai.android.files.settings.PathSettings
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat
import java.io.File

/**
 * Isolated local directory for UI smoke tests. No network paths, no keystores, no production
 * sample images.
 *
 * [reset] clears open tabs and folder view/sort so each test starts clean. Callers must save
 * those settings first and write them back in `@After`, including when the suite runs on a
 * shared emulator.
 */
object UiSmokeFixture {
    const val ROOT_NAME = "MFExplorerUiSmoke"
    const val IMAGE_NAME = "zzz-smoke.png"
    const val FIRST_FOLDER = "aaa-00"
    const val LAST_FOLDER = "aaa-23"
    const val NEW_FOLDER = "zzz-ui-smoke"
    const val NEW_FILE = "zzz-ui-smoke-file.txt"
    const val RENAMED_FILE = "zzz-ui-smoke-renamed.txt"
    const val DEEP_FILE = "deep-smoke.txt"
    const val BROKEN_JPEG_NAME = "kaputt.jpg"
    const val TRUNCATED_JPEG_NAME = "cut.jpg"
    const val BROKEN_HEIC_NAME = "bad.heic"
    const val PAD_COUNT = 24

    /**
     * Solid-color PNGs used by the pager swipe test. Width and height are chosen so
     * [me.zhanghai.android.files.viewer.image.ImageInfo.shouldUseLargeImageView] is true
     * (edge longer than 2048 px and aspect ratio greater than 2). A 4000×3000 image would
     * stay on the small PhotoView path.
     */
    val SWIPE_IMAGES: List<Pair<String, Int>> = listOf(
        "swipe-00.png" to Color.rgb(229, 57, 53),
        "swipe-01.png" to Color.rgb(30, 136, 229),
        "swipe-02.png" to Color.rgb(67, 160, 71),
        "swipe-03.png" to Color.rgb(253, 216, 53),
        "swipe-04.png" to Color.rgb(142, 36, 170),
        "swipe-05.png" to Color.rgb(0, 172, 193),
        "swipe-06.png" to Color.rgb(251, 140, 0),
        "swipe-07.png" to Color.rgb(69, 90, 100)
    )
    const val SWIPE_IMAGE_WIDTH = 4096
    const val SWIPE_IMAGE_HEIGHT = 1024

    data class SavedAppState(
        val openTabs: OpenTabs?,
        val animation: Boolean,
        val viewType: FileViewType,
        val sortOptions: FileSortOptions
    )

    fun rootDir(): File = File(android.os.Environment.getExternalStorageDirectory(), ROOT_NAME)

    fun imageFile(): File = File(rootDir(), IMAGE_NAME)

    fun brokenJpegFile(): File = File(rootDir(), BROKEN_JPEG_NAME)

    fun truncatedJpegFile(): File = File(rootDir(), TRUNCATED_JPEG_NAME)

    fun brokenHeicFile(): File = File(rootDir(), BROKEN_HEIC_NAME)

    fun swipeImageFile(name: String): File = File(rootDir(), name)

    fun saveAppState(): SavedAppState =
        SavedAppState(
            openTabs = Settings.FILE_LIST_OPEN_TABS.value,
            animation = Settings.FILE_LIST_ANIMATION.valueCompat,
            viewType = Settings.FILE_LIST_VIEW_TYPE.valueCompat,
            sortOptions = Settings.FILE_LIST_SORT_OPTIONS.valueCompat
        )

    fun restoreAppState(state: SavedAppState) {
        Settings.FILE_LIST_OPEN_TABS.putValue(state.openTabs)
        Settings.FILE_LIST_ANIMATION.putValue(state.animation)
        Settings.FILE_LIST_VIEW_TYPE.putValue(state.viewType)
        Settings.FILE_LIST_SORT_OPTIONS.putValue(state.sortOptions)
        val path = Paths.get(rootDir().absolutePath)
        PathSettings.getFileListViewType(path).putValue(null)
        PathSettings.getFileListSortOptions(path).putValue(null)
    }

    fun deleteRoot() {
        val root = rootDir()
        if (root.exists()) {
            root.deleteRecursively()
        }
    }

    fun reset() {
        Settings.FILE_LIST_OPEN_TABS.putValue(null)
        Settings.FILE_LIST_ANIMATION.putValue(false)
        Settings.FILE_LIST_VIEW_TYPE.putValue(FileViewType.LIST)
        Settings.FILE_LIST_SORT_OPTIONS.putValue(
            FileSortOptions(FileSortOptions.By.NAME, FileSortOptions.Order.ASCENDING, true)
        )
        val path = Paths.get(rootDir().absolutePath)
        PathSettings.getFileListViewType(path).putValue(null)
        PathSettings.getFileListSortOptions(path).putValue(null)
        deleteRoot()
        val root = rootDir()
        check(root.mkdirs()) { "Could not create ${root.absolutePath}" }
        for (index in 0 until PAD_COUNT) {
            val folder = File(root, "aaa-%02d".format(index))
            check(folder.mkdir()) { "Could not create ${folder.absolutePath}" }
        }
        writePng(imageFile(), 128, 128, Color.rgb(46, 125, 50))
        val deepFile = File(File(root, FIRST_FOLDER), DEEP_FILE)
        deepFile.writeText("local smoke fixture\n")
    }

    fun writeBrokenImages() {
        brokenJpegFile().writeText("this is not a jpeg\n")
        val jpeg = File.createTempFile("smoke-jpeg", ".jpg", rootDir())
        writeJpeg(jpeg, 256, 256, Color.rgb(33, 150, 243))
        val bytes = jpeg.readBytes()
        jpeg.delete()
        truncatedJpegFile().writeBytes(bytes.copyOf(bytes.size / 2))
        brokenHeicFile().writeText("this is not a heic\n")
    }

    fun writeSwipeImages() {
        for ((name, color) in SWIPE_IMAGES) {
            writePng(swipeImageFile(name), SWIPE_IMAGE_WIDTH, SWIPE_IMAGE_HEIGHT, color)
        }
    }

    fun viewListIntent(): Intent =
        FileListActivity.createViewIntent(Paths.get(rootDir().absolutePath))

    private fun writePng(file: File, width: Int, height: Int, color: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        file.outputStream().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                "Could not write ${file.absolutePath}"
            }
        }
        bitmap.recycle()
    }

    private fun writeJpeg(file: File, width: Int, height: Int, color: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        file.outputStream().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)) {
                "Could not write ${file.absolutePath}"
            }
        }
        bitmap.recycle()
    }
}
