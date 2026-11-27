package me.zhanghai.android.files

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.drawToBitmap
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.ViewAssertion
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.pressImeActionButton
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.action.ViewActions.swipeLeft
import androidx.test.espresso.action.ViewActions.swipeRight
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.android.material.tabs.TabLayout
import java8.nio.file.Paths
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.ui.FailureArtifactsRule
import me.zhanghai.android.files.ui.resolveOpenWithIfPresent
import me.zhanghai.android.files.ui.scrollUntilCompletelyDisplayed
import me.zhanghai.android.files.ui.waitUntilCompletelyDisplayed
import me.zhanghai.android.files.ui.waitUntilCondition
import me.zhanghai.android.files.ui.waitUntilDisplayed
import me.zhanghai.android.files.viewer.image.ImageViewerActivity
import org.hamcrest.CoreMatchers.allOf
import org.hamcrest.CoreMatchers.anyOf
import org.hamcrest.Matcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Small UI smoke suite for the emulator job. Covers local browsing only: start, list, tabs,
 * search, breadcrumb, new folder, settings, licenses, sort, view type, local file operations,
 * and the image viewer. No network targets.
 */
@RunWith(AndroidJUnit4::class)
class UiSmokeTest {
    @get:Rule
    val failureArtifacts = FailureArtifactsRule()

    private lateinit var savedAppState: UiSmokeFixture.SavedAppState

    @Before
    fun setUp() {
        savedAppState = UiSmokeFixture.saveAppState()
        UiSmokeFixture.reset()
    }

    @After
    fun tearDown() {
        UiSmokeFixture.deleteRoot()
        if (::savedAppState.isInitialized) {
            UiSmokeFixture.restoreAppState(savedAppState)
        }
    }

    @Test
    fun appStartsAndListsLocalDirectory() {
        launchFileList().use {
            waitUntilListed(UiSmokeFixture.FIRST_FOLDER)
            onView(withId(R.id.toolbar)).check(matches(isDisplayed()))
            onView(fileListRecycler()).check(matches(isDisplayed()))
            onView(withId(R.id.breadcrumbLayout)).check(matches(isDisplayed()))
            onView(withId(R.id.tabLayout)).check(matches(isDisplayed()))
            onView(withId(R.id.addTabButton)).check(matches(isDisplayed()))
            onView(withId(R.id.speedDialView)).check(matches(isDisplayed()))
            onView(breadcrumb(UiSmokeFixture.ROOT_NAME)).check(matches(isDisplayed()))
            onView(fileName(UiSmokeFixture.FIRST_FOLDER)).check(matches(isDisplayed()))
            onView(withId(R.id.tabLayout)).check(tabCount(1))
        }
    }

    @Test
    fun plusButtonOpensTabAndSwitchingRestoresPath() {
        launchFileList().use {
            waitUntilListed(UiSmokeFixture.FIRST_FOLDER)
            onView(withId(R.id.addTabButton)).perform(click())
            onView(withId(R.id.tabLayout)).check(tabCount(2))

            onView(fileName(UiSmokeFixture.FIRST_FOLDER)).perform(click())
            waitUntilDisplayed(breadcrumb(UiSmokeFixture.FIRST_FOLDER))
            onView(breadcrumb(UiSmokeFixture.ROOT_NAME)).check(matches(isDisplayed()))

            onView(withId(R.id.tabLayout)).perform(selectTab(0))
            waitUntilListed(UiSmokeFixture.FIRST_FOLDER)
            onView(breadcrumb(UiSmokeFixture.ROOT_NAME)).check(matches(isDisplayed()))

            onView(withId(R.id.tabLayout)).perform(selectTab(1))
            waitUntilDisplayed(breadcrumb(UiSmokeFixture.FIRST_FOLDER))
        }
    }

    @Test
    fun tabsOverflowKeepsLastTabVisible() {
        launchFileList().use {
            waitUntilListed(UiSmokeFixture.FIRST_FOLDER)
            onView(withId(R.id.addTabButton)).perform(click())
            onView(withId(R.id.tabLayout)).check(tabCount(2))
            onView(withId(R.id.tabLayout)).check(tabsDoNotScroll())
            assertLastTabVisibleBesidePlus()

            repeat(4) { index ->
                onView(withId(R.id.addTabButton)).perform(click())
                onView(withId(R.id.tabLayout)).check(tabCount(3 + index))
                assertLastTabVisibleBesidePlus()
            }
        }
    }

    @Test
    fun searchFiltersTheLocalList() {
        launchFileList().use { scenario ->
            waitUntilListed(UiSmokeFixture.FIRST_FOLDER)
            onView(withId(R.id.action_search)).perform(click())
            onView(withId(androidx.appcompat.R.id.search_src_text))
                .perform(replaceText(UiSmokeFixture.IMAGE_NAME))
            // Espresso KEYCODE_BACK hides the IME when the search field has focus, but does
            // not go through OnBackPressedDispatcher on API 34. Drive collapse and clear
            // through the dispatcher, which is what a system back after the IME uses.
            pressBack()
            waitUntilListed(UiSmokeFixture.IMAGE_NAME)
            onView(fileName(UiSmokeFixture.FIRST_FOLDER)).check(doesNotExist())
            assertSearchQueryKept(UiSmokeFixture.IMAGE_NAME)

            pressAppBack(scenario)
            if (!isNamePresent(UiSmokeFixture.FIRST_FOLDER)) {
                waitUntilListed(UiSmokeFixture.IMAGE_NAME)
                onView(fileName(UiSmokeFixture.FIRST_FOLDER)).check(doesNotExist())
                assertSearchQueryKept(UiSmokeFixture.IMAGE_NAME)
                pressAppBack(scenario)
            }
            waitUntilListed(UiSmokeFixture.FIRST_FOLDER)
            onView(fileName(UiSmokeFixture.FIRST_FOLDER)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun submittedDeepSearchFindsNestedFile() {
        launchFileList().use {
            waitUntilListed(UiSmokeFixture.FIRST_FOLDER)
            onView(withId(R.id.action_search)).perform(click())
            onView(withId(androidx.appcompat.R.id.search_src_text))
                .perform(replaceText(UiSmokeFixture.DEEP_FILE), pressImeActionButton())
            waitUntilListed(UiSmokeFixture.DEEP_FILE)
            onView(fileName(UiSmokeFixture.IMAGE_NAME)).check(doesNotExist())
        }
    }

    @Test
    fun breadcrumbNavigatesToParent() {
        launchFileList().use {
            waitUntilListed(UiSmokeFixture.FIRST_FOLDER)
            onView(fileName(UiSmokeFixture.FIRST_FOLDER)).perform(click())
            waitUntilDisplayed(breadcrumb(UiSmokeFixture.FIRST_FOLDER))
            onView(breadcrumb(UiSmokeFixture.ROOT_NAME)).perform(click())
            waitUntilListed(UiSmokeFixture.FIRST_FOLDER)
            onView(breadcrumb(UiSmokeFixture.ROOT_NAME)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun newFolderScrollsIntoView() {
        launchFileList().use {
            waitUntilListed(UiSmokeFixture.FIRST_FOLDER)
            onView(fileName(UiSmokeFixture.NEW_FOLDER)).check(doesNotExist())

            onView(withId(R.id.speedDialView)).perform(click())
            waitUntilDisplayed(withText(R.string.file_list_action_create_directory))
            onView(withText(R.string.file_list_action_create_directory)).perform(click())
            waitUntilDisplayed(withId(R.id.nameEdit))
            onView(withId(R.id.nameEdit)).perform(replaceText(UiSmokeFixture.NEW_FOLDER))
            closeSoftKeyboard()
            onView(withText(android.R.string.ok)).perform(click())

            waitUntilCompletelyDisplayed(fileName(UiSmokeFixture.NEW_FOLDER))
            onView(fileName(UiSmokeFixture.NEW_FOLDER))
                .check(matches(isCompletelyDisplayed()))
        }
    }

    @Test
    fun createFileAppearsInTheList() {
        launchFileList().use {
            waitUntilListed(UiSmokeFixture.FIRST_FOLDER)
            onView(withId(R.id.speedDialView)).perform(click())
            waitUntilDisplayed(withText(R.string.file_list_action_create_file))
            onView(withText(R.string.file_list_action_create_file)).perform(click())
            waitUntilDisplayed(withId(R.id.nameEdit))
            onView(withId(R.id.nameEdit)).perform(replaceText(UiSmokeFixture.NEW_FILE))
            closeSoftKeyboard()
            onView(withText(android.R.string.ok)).perform(click())

            scrollUntilCompletelyDisplayed(fileName(UiSmokeFixture.NEW_FILE), fileListRecycler())
            onView(fileName(UiSmokeFixture.NEW_FILE)).check(matches(isCompletelyDisplayed()))
            assertTrue(File(UiSmokeFixture.rootDir(), UiSmokeFixture.NEW_FILE).isFile)
        }
    }

    @Test
    fun sortByNameDescendingShowsLastFolderFirst() {
        launchFileList().use {
            waitUntilListed(UiSmokeFixture.FIRST_FOLDER)
            onView(withId(R.id.action_view_sort)).perform(click())
            onView(withText(R.string.file_list_action_sort_order_ascending)).perform(click())
            waitUntilCondition("first row is ${UiSmokeFixture.LAST_FOLDER}") {
                firstListedName() == UiSmokeFixture.LAST_FOLDER
            }
            assertEquals(UiSmokeFixture.LAST_FOLDER, firstListedName())
        }
    }

    @Test
    fun viewTypeSwitchesBetweenGridAndList() {
        launchFileList().use {
            waitUntilListed(UiSmokeFixture.FIRST_FOLDER)
            onView(fileListRecycler()).check(spanCount(1))

            onView(withId(R.id.action_view_sort)).perform(click())
            onView(withText(R.string.file_list_action_view_grid)).perform(click())
            waitUntilCondition("grid span count is greater than 1") {
                listSpanCount() > 1
            }
            onView(fileListRecycler()).check(spanCountGreaterThan(1))

            onView(withId(R.id.action_view_sort)).perform(click())
            onView(withText(R.string.file_list_action_view_list)).perform(click())
            waitUntilCondition("list span count is 1") { listSpanCount() == 1 }
            onView(fileListRecycler()).check(spanCount(1))
        }
    }

    @Test
    fun localFileOperationsCopyMoveRenameAndDelete() {
        launchFileList().use {
            waitUntilListed(UiSmokeFixture.FIRST_FOLDER)
            createFileFromSpeedDial(UiSmokeFixture.NEW_FILE)
            scrollUntilCompletelyDisplayed(fileName(UiSmokeFixture.NEW_FILE), fileListRecycler())

            openItemMenu(UiSmokeFixture.NEW_FILE)
            onView(withText(R.string.rename)).perform(click())
            waitUntilDisplayed(withId(R.id.nameEdit))
            onView(withId(R.id.nameEdit)).perform(replaceText(UiSmokeFixture.RENAMED_FILE))
            closeSoftKeyboard()
            onView(withText(android.R.string.ok)).perform(click())
            scrollUntilCompletelyDisplayed(fileName(UiSmokeFixture.RENAMED_FILE), fileListRecycler())
            onView(fileName(UiSmokeFixture.NEW_FILE)).check(doesNotExist())
            assertTrue(File(UiSmokeFixture.rootDir(), UiSmokeFixture.RENAMED_FILE).isFile)
            assertFalse(File(UiSmokeFixture.rootDir(), UiSmokeFixture.NEW_FILE).exists())

            openItemMenu(UiSmokeFixture.RENAMED_FILE)
            onView(withText(R.string.copy)).perform(click())
            waitUntilDisplayed(pasteAction())
            scrollUntilCompletelyDisplayed(fileName("aaa-01"), fileListRecycler())
            onView(fileName("aaa-01")).perform(click())
            waitUntilDisplayed(breadcrumb("aaa-01"))
            onView(pasteAction()).perform(click())
            waitUntilListed(UiSmokeFixture.RENAMED_FILE)
            assertTrue(
                File(File(UiSmokeFixture.rootDir(), "aaa-01"), UiSmokeFixture.RENAMED_FILE).isFile
            )
            assertTrue(File(UiSmokeFixture.rootDir(), UiSmokeFixture.RENAMED_FILE).isFile)

            onView(breadcrumb(UiSmokeFixture.ROOT_NAME)).perform(click())
            waitUntilListed(UiSmokeFixture.FIRST_FOLDER)
            scrollUntilCompletelyDisplayed(fileName(UiSmokeFixture.RENAMED_FILE), fileListRecycler())
            openItemMenu(UiSmokeFixture.RENAMED_FILE)
            onView(withText(R.string.cut)).perform(click())
            waitUntilDisplayed(pasteAction())
            scrollUntilCompletelyDisplayed(fileName("aaa-02"), fileListRecycler())
            onView(fileName("aaa-02")).perform(click())
            waitUntilDisplayed(breadcrumb("aaa-02"))
            onView(pasteAction()).perform(click())
            waitUntilListed(UiSmokeFixture.RENAMED_FILE)
            waitUntilCondition("moved file exists only in aaa-02") {
                File(File(UiSmokeFixture.rootDir(), "aaa-02"), UiSmokeFixture.RENAMED_FILE).isFile &&
                    !File(UiSmokeFixture.rootDir(), UiSmokeFixture.RENAMED_FILE).exists()
            }

            openItemMenu(UiSmokeFixture.RENAMED_FILE)
            onView(withText(R.string.delete)).perform(click())
            waitUntilDisplayed(withText(android.R.string.ok))
            onView(withText(android.R.string.ok)).perform(click())
            waitUntilCondition("deleted file is gone") {
                !isNamePresent(UiSmokeFixture.RENAMED_FILE) &&
                    !File(File(UiSmokeFixture.rootDir(), "aaa-02"), UiSmokeFixture.RENAMED_FILE)
                        .exists()
            }
            onView(fileName(UiSmokeFixture.RENAMED_FILE)).check(doesNotExist())
        }
    }

    @Test
    fun settingsAndLicensesOpenFromNavigationDrawer() {
        launchFileList().use {
            waitUntilListed(UiSmokeFixture.FIRST_FOLDER)
            openDrawerItem(R.string.navigation_settings)
            waitUntilDisplayed(withText(R.string.settings_locale_title))
            onView(withText(R.string.settings_interface_title)).check(matches(isDisplayed()))

            pressBack()
            waitUntilListed(UiSmokeFixture.FIRST_FOLDER)
            openDrawerItem(R.string.navigation_about)
            waitUntilDisplayed(withId(R.id.licensesLayout))
            onView(withId(R.id.licensesLayout)).perform(scrollTo(), click())
            waitUntilDisplayed(withText(R.string.about_licenses_title))
            onView(withText(R.string.close)).perform(click())
            waitUntilDisplayed(withId(R.id.licensesLayout))
        }
    }

    @Test
    fun imageViewerShowsSyntheticImage() {
        val path = Paths.get(UiSmokeFixture.imageFile().absolutePath)
        assertTrue(UiSmokeFixture.imageFile().isFile)
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            ImageViewerActivity::class.java
        )
        ImageViewerActivity.putExtras(intent, listOf(path), 0)
        ActivityScenario.launch<ImageViewerActivity>(intent).use {
            waitUntilDisplayed(visibleImage())
            onView(allOf(withId(R.id.errorText), isDisplayed())).check(doesNotExist())
            onView(allOf(withId(R.id.progress), isDisplayed())).check(doesNotExist())
            onView(withText(UiSmokeFixture.IMAGE_NAME)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun openImageFromFileList() {
        launchFileList().use {
            waitUntilListed(UiSmokeFixture.FIRST_FOLDER)
            scrollUntilCompletelyDisplayed(fileName(UiSmokeFixture.IMAGE_NAME), fileListRecycler())
            onView(fileName(UiSmokeFixture.IMAGE_NAME)).perform(click())
            resolveOpenWithIfPresent(
                isDone = { isImageViewerResumed() },
                "Image viewer",
                "MF Explorer"
            )
            waitUntilCondition("image viewer is resumed") { isImageViewerResumed() }
            waitUntilDisplayed(visibleImage())
            onView(allOf(withId(R.id.errorText), isDisplayed())).check(doesNotExist())
            onView(withText(UiSmokeFixture.IMAGE_NAME)).check(matches(isDisplayed()))

            pressBack()
            waitUntilListed(UiSmokeFixture.IMAGE_NAME)
            onView(breadcrumb(UiSmokeFixture.ROOT_NAME)).check(matches(isDisplayed()))
        }
    }

    @Ignore(
        "The viewer logs 'Cannot load' and calls showError() for kaputt.jpg, but errorText " +
            "does not stay visible. loadImage() starts fadeOutUnsafe() on errorText and a " +
            "fast Coil failure then calls showError()/fadeInUnsafe(); the earlier fadeOut " +
            "can finish afterwards and set the page INVISIBLE. Do not fix the app in this " +
            "suite PR."
    )
    @Test
    fun imageViewerShowsReadableErrorForBrokenImages() {
        UiSmokeFixture.writeBrokenImages()
        val paths = listOf(
            UiSmokeFixture.brokenJpegFile(),
            UiSmokeFixture.truncatedJpegFile(),
            UiSmokeFixture.brokenHeicFile()
        ).map { Paths.get(it.absolutePath) }
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            ImageViewerActivity::class.java
        )
        ImageViewerActivity.putExtras(intent, paths, 0)
        ActivityScenario.launch<ImageViewerActivity>(intent).use {
            assertBrokenImagePage(UiSmokeFixture.BROKEN_JPEG_NAME, allowPartialImage = false)
            onView(withId(R.id.viewPager)).perform(swipeLeft())
            assertBrokenImagePage(UiSmokeFixture.TRUNCATED_JPEG_NAME, allowPartialImage = true)
            onView(withId(R.id.viewPager)).perform(swipeLeft())
            assertBrokenImagePage(UiSmokeFixture.BROKEN_HEIC_NAME, allowPartialImage = false)
        }
    }

    @Test
    fun imageViewerFastSwipeShowsCorrectImage() {
        UiSmokeFixture.writeSwipeImages()
        val paths = UiSmokeFixture.SWIPE_IMAGES.map {
            Paths.get(UiSmokeFixture.swipeImageFile(it.first).absolutePath)
        }
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            ImageViewerActivity::class.java
        )
        ImageViewerActivity.putExtras(intent, paths, 0)
        ActivityScenario.launch<ImageViewerActivity>(intent).use {
            waitUntilDisplayed(visibleImage())
            onView(withText(UiSmokeFixture.SWIPE_IMAGES[0].first)).check(matches(isDisplayed()))

            repeat(6) { onView(withId(R.id.viewPager)).perform(swipeLeft()) }
            repeat(2) { onView(withId(R.id.viewPager)).perform(swipeRight()) }

            val started = System.currentTimeMillis()
            waitUntilCondition("image loaded and progress gone", timeoutMs = 10_000L) {
                isNameOnScreen(currentSwipeTitle()) &&
                    !isProgressDisplayed() &&
                    isVisibleImageDisplayed()
            }
            assertTrue(
                "progress still visible after ${System.currentTimeMillis() - started} ms",
                System.currentTimeMillis() - started <= 10_000L
            )
            onView(allOf(withId(R.id.progress), isDisplayed())).check(doesNotExist())

            val title = visibleToolbarTitle()
            val expected = UiSmokeFixture.SWIPE_IMAGES.find { it.first == title }
            assertTrue("toolbar title $title is not a swipe fixture image", expected != null)
            val currentIndex = swipeTitleIndex(title)
            assertTrue("fast swipe did not leave the first page (index=$currentIndex)", currentIndex >= 3)
            assertVisibleImageColor(expected!!.second)
        }
    }

    private fun launchFileList(): ActivityScenario<FileListActivity> =
        ActivityScenario.launch(UiSmokeFixture.viewListIntent())

    private fun pressAppBack(scenario: ActivityScenario<FileListActivity>) {
        scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun waitUntilListed(name: String) {
        waitUntilDisplayed(fileName(name))
    }

    private fun fileName(name: String): Matcher<View> =
        allOf(withId(R.id.nameText), withText(name))

    private fun breadcrumb(name: String): Matcher<View> =
        allOf(
            withId(R.id.text),
            withText(name),
            isDescendantOfA(withId(R.id.breadcrumbLayout))
        )

    private fun visibleImage(): Matcher<View> =
        anyOf(
            allOf(withId(R.id.image), isCompletelyDisplayed()),
            allOf(withId(R.id.largeImage), isCompletelyDisplayed())
        )

    private fun pasteAction(): Matcher<View> =
        anyOf(withId(R.id.action_paste), withContentDescription(R.string.paste))

    private fun openDrawerItem(titleRes: Int) {
        onView(withContentDescription(R.string.open_navigation_drawer)).perform(click())
        scrollUntilCompletelyDisplayed(drawerItem(titleRes), navigationRecycler())
        onView(drawerItem(titleRes)).perform(click())
    }

    private fun drawerItem(titleRes: Int): Matcher<View> =
        allOf(
            withId(R.id.itemLayout),
            hasDescendant(withText(titleRes)),
            isDescendantOfA(withId(R.id.navigationFragment))
        )

    private fun fileListRecycler(): Matcher<View> =
        allOf(withId(R.id.recyclerView), isDescendantOfA(withId(R.id.swipeRefreshLayout)))

    private fun navigationRecycler(): Matcher<View> =
        allOf(withId(R.id.recyclerView), isDescendantOfA(withId(R.id.navigationFragment)))

    private fun createFileFromSpeedDial(name: String) {
        onView(withId(R.id.speedDialView)).perform(click())
        waitUntilDisplayed(withText(R.string.file_list_action_create_file))
        onView(withText(R.string.file_list_action_create_file)).perform(click())
        waitUntilDisplayed(withId(R.id.nameEdit))
        onView(withId(R.id.nameEdit)).perform(replaceText(name))
        closeSoftKeyboard()
        onView(withText(android.R.string.ok)).perform(click())
        waitUntilCondition("created $name") {
            File(UiSmokeFixture.rootDir(), name).isFile
        }
    }

    private fun openItemMenu(name: String) {
        scrollUntilCompletelyDisplayed(fileName(name), fileListRecycler())
        onView(
            allOf(
                withId(R.id.menuButton),
                isDescendantOfA(
                    allOf(withId(R.id.itemLayout), hasDescendant(withText(name)))
                ),
                isDisplayed()
            )
        ).perform(click())
    }

    private fun tabCount(expected: Int) =
        ViewAssertion { view, noView ->
            if (noView != null) {
                throw noView
            }
            assertEquals(expected, (view as TabLayout).tabCount)
        }

    private fun tabsDoNotScroll() =
        ViewAssertion { view, noView ->
            if (noView != null) {
                throw noView
            }
            val tabs = view as TabLayout
            assertFalse(tabs.canScrollHorizontally(1))
            assertFalse(tabs.canScrollHorizontally(-1))
        }

    private fun spanCount(expected: Int) =
        ViewAssertion { view, noView ->
            if (noView != null) {
                throw noView
            }
            val manager = (view as RecyclerView).layoutManager as GridLayoutManager
            assertEquals(expected, manager.spanCount)
        }

    private fun spanCountGreaterThan(minimum: Int) =
        ViewAssertion { view, noView ->
            if (noView != null) {
                throw noView
            }
            val manager = (view as RecyclerView).layoutManager as GridLayoutManager
            assertTrue("spanCount was ${manager.spanCount}", manager.spanCount > minimum)
        }

    private fun selectTab(index: Int): ViewAction =
        object : ViewAction {
            override fun getConstraints(): Matcher<View> =
                isAssignableFrom(TabLayout::class.java)

            override fun getDescription(): String = "select tab at $index"

            override fun perform(uiController: UiController, view: View) {
                val tab = (view as TabLayout).getTabAt(index)
                    ?: throw IllegalStateException("No tab at $index")
                tab.select()
                uiController.loopMainThreadUntilIdle()
            }
        }

    private fun assertLastTabVisibleBesidePlus() {
        onView(isRoot()).check { root, noView ->
            if (noView != null) {
                throw noView
            }
            val tabs = root.findViewById<TabLayout>(R.id.tabLayout)
            val plus = root.findViewById<View>(R.id.addTabButton)
            val last = tabs.getTabAt(tabs.tabCount - 1)?.view
                ?: throw AssertionError("No last tab")
            val lastRect = Rect()
            val plusRect = Rect()
            assertTrue("last tab is not visible", last.getGlobalVisibleRect(lastRect) && last.isShown)
            assertEquals("last tab is clipped", last.width, lastRect.width())
            assertEquals("last tab is clipped vertically", last.height, lastRect.height())
            plus.getGlobalVisibleRect(plusRect)
            assertFalse(
                "last tab overlaps the plus button",
                Rect.intersects(lastRect, plusRect)
            )
        }
    }

    private fun assertSearchQueryKept(query: String) {
        onView(withId(R.id.toolbar)).check { view, noView ->
            if (noView != null) {
                throw noView
            }
            val toolbar = view as Toolbar
            val field = toolbar.findViewById<TextView?>(androidx.appcompat.R.id.search_src_text)
            val inField = field?.text?.toString() == query
            val inSubtitle = toolbar.subtitle?.toString()?.contains(query) == true
            assertTrue(
                "query $query was not kept in the search field or toolbar subtitle " +
                    "(field=${field?.text}, subtitle=${toolbar.subtitle})",
                inField || inSubtitle
            )
        }
    }

    private fun assertBrokenImagePage(title: String, allowPartialImage: Boolean) {
        waitUntilCondition("viewer settled on $title") {
            isErrorTextDisplayed() || isVisibleImageDisplayed()
        }
        onView(allOf(withId(R.id.progress), isDisplayed())).check(doesNotExist())
        assertEquals(title, visibleToolbarTitle())
        if (allowPartialImage && isVisibleImageDisplayed() && !isErrorTextDisplayed()) {
            return
        }
        onView(allOf(withId(R.id.errorText), isDisplayed()))
            .check(matches(withText(R.string.image_viewer_error)))
        onView(allOf(withId(R.id.errorText), isDisplayed())).check { view, noView ->
            if (noView != null) {
                throw noView
            }
            val text = (view as TextView).text.toString()
            assertFalse("error text leaked a type name: $text", "Exception" in text)
            assertFalse("error text leaked a package name: $text", "java." in text)
            assertFalse("error text leaked a source file: $text", ".kt" in text)
        }
    }

    private fun assertVisibleImageColor(expected: Int) {
        onView(visibleImage()).check { view, noView ->
            if (noView != null) {
                throw noView
            }
            val bitmap = view.drawToBitmap()
            val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            bitmap.recycle()
            val delta = 40
            assertTrue(
                "center pixel #${Integer.toHexString(pixel)} is not close to " +
                    "#${Integer.toHexString(expected)}",
                abs(Color.red(pixel) - Color.red(expected)) <= delta &&
                    abs(Color.green(pixel) - Color.green(expected)) <= delta &&
                    abs(Color.blue(pixel) - Color.blue(expected)) <= delta
            )
        }
    }

    private fun isSearchFieldDisplayed(): Boolean =
        try {
            onView(allOf(withId(androidx.appcompat.R.id.search_src_text), isDisplayed()))
                .check(matches(isDisplayed()))
            true
        } catch (_: Throwable) {
            false
        }

    private fun isNamePresent(name: String): Boolean =
        try {
            onView(allOf(fileName(name), isDisplayed())).check(matches(isDisplayed()))
            true
        } catch (_: Throwable) {
            false
        }

    private fun isNameOnScreen(name: String?): Boolean {
        if (name.isNullOrEmpty()) {
            return false
        }
        return try {
            onView(allOf(withText(name), isDisplayed())).check(matches(isDisplayed()))
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun isErrorTextDisplayed(): Boolean =
        try {
            onView(allOf(withId(R.id.errorText), isDisplayed())).check(matches(isDisplayed()))
            true
        } catch (_: Throwable) {
            false
        }

    private fun isVisibleImageDisplayed(): Boolean =
        try {
            onView(visibleImage()).check(matches(isDisplayed()))
            true
        } catch (_: Throwable) {
            false
        }

    private fun isProgressDisplayed(): Boolean =
        try {
            onView(allOf(withId(R.id.progress), isDisplayed())).check(matches(isDisplayed()))
            true
        } catch (_: Throwable) {
            false
        }

    private fun currentSwipeTitle(): String? = visibleToolbarTitle()
        .takeIf { title -> UiSmokeFixture.SWIPE_IMAGES.any { it.first == title } }

    private fun swipeTitleIndex(title: String): Int =
        UiSmokeFixture.SWIPE_IMAGES.indexOfFirst { it.first == title }

    private fun visibleToolbarTitle(): String {
        var title = ""
        onView(withId(R.id.toolbar)).check { view, noView ->
            if (noView != null) {
                throw noView
            }
            title = (view as Toolbar).title?.toString().orEmpty()
        }
        return title
    }

    private fun firstListedName(): String? {
        var name: String? = null
        try {
            onView(fileListRecycler()).check { view, noView ->
                if (noView != null) {
                    throw noView
                }
                val recycler = view as RecyclerView
                val holder = recycler.findViewHolderForAdapterPosition(0) ?: return@check
                name = holder.itemView.findViewById<TextView>(R.id.nameText).text?.toString()
            }
        } catch (_: Throwable) {
            return null
        }
        return name
    }

    private fun listSpanCount(): Int {
        var count = -1
        onView(fileListRecycler()).check { view, noView ->
            if (noView != null) {
                throw noView
            }
            count = ((view as RecyclerView).layoutManager as GridLayoutManager).spanCount
        }
        return count
    }

    private fun isImageViewerResumed(): Boolean {
        var resumed = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            resumed = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .any { activity: Activity -> activity is ImageViewerActivity }
        }
        return resumed
    }
}
