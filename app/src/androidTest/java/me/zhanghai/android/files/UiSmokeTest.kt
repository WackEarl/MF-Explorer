package me.zhanghai.android.files

import android.content.Intent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.tabs.TabLayout
import java8.nio.file.Paths
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.ui.scrollUntilCompletelyDisplayed
import me.zhanghai.android.files.ui.waitUntilCompletelyDisplayed
import me.zhanghai.android.files.ui.waitUntilDisplayed
import me.zhanghai.android.files.viewer.image.ImageViewerActivity
import org.hamcrest.CoreMatchers.allOf
import org.hamcrest.CoreMatchers.anyOf
import org.hamcrest.Matcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Small UI smoke suite for the emulator job. Covers local browsing only: start, list, tabs,
 * search, breadcrumb, new folder, settings, licenses, and the image viewer. No network targets.
 */
@RunWith(AndroidJUnit4::class)
class UiSmokeTest {
    @Before
    fun setUp() {
        UiSmokeFixture.reset()
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
        }
    }

    @Test
    fun searchFiltersTheLocalList() {
        launchFileList().use {
            waitUntilListed(UiSmokeFixture.FIRST_FOLDER)
            onView(withId(R.id.action_search)).perform(click())
            onView(withId(androidx.appcompat.R.id.search_src_text))
                .perform(replaceText(UiSmokeFixture.IMAGE_NAME))
            closeSoftKeyboard()
            waitUntilListed(UiSmokeFixture.IMAGE_NAME)
            onView(fileName(UiSmokeFixture.FIRST_FOLDER)).check(doesNotExist())
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
            waitUntilDisplayed(
                anyOf(
                    allOf(withId(R.id.image), isDisplayed()),
                    allOf(withId(R.id.largeImage), isDisplayed())
                )
            )
            onView(allOf(withId(R.id.errorText), isDisplayed())).check(doesNotExist())
            onView(withText(UiSmokeFixture.IMAGE_NAME)).check(matches(isDisplayed()))
        }
    }

    private fun launchFileList(): ActivityScenario<FileListActivity> =
        ActivityScenario.launch(UiSmokeFixture.viewListIntent())

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

    private fun tabCount(expected: Int) =
        androidx.test.espresso.ViewAssertion { view, noView ->
            if (noView != null) {
                throw noView
            }
            assertEquals(expected, (view as TabLayout).tabCount)
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
}
