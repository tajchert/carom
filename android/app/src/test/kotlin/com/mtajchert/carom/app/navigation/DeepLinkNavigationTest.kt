package com.mtajchert.carom.app.navigation

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.core.net.toUri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mtajchert.carom.app.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

// =============================================================================
// DEEP LINK AND BACK NAVIGATION TESTS
// =============================================================================
// These launch the real MainActivity with a real Intent, through Robolectric,
// with Hilt building the real object graph. That covers the whole path a deep
// link actually takes - Intent, validation, synthetic back stack, rendered
// screen - rather than any one layer in isolation.
//
// The rules under test are the ones that are easy to get wrong and invisible
// until a user hits them:
//
// - A deep-linked detail screen must leave the list beneath it, so Back returns
//   to the app rather than to whatever launched the link.
// - Back on the root entry must not empty the back stack.
// - A URI this app does not own must not navigate anywhere special.
//
// `createEmptyComposeRule` is used instead of `createAndroidComposeRule` because
// each case needs to supply its own launch Intent.
// =============================================================================

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class)
class DeepLinkNavigationTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun `launching without a deep link starts on the task list`() {
        launch(uri = null).use {
            composeRule.onNodeWithText("Tasks").assertIsDisplayed()
        }
    }

    @Test
    fun `a task deep link opens the detail screen`() {
        launch(uri = "https://tajchert.github.io/tasks/abc-123").use {
            composeRule.onNodeWithText("Task Details").assertIsDisplayed()
        }
    }

    @Test
    fun `back from a deep linked detail returns to the list, not out of the app`() {
        launch(uri = "https://tajchert.github.io/tasks/abc-123").use { scenario ->
            composeRule.onNodeWithText("Task Details").assertIsDisplayed()

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            composeRule.waitForIdle()

            // The synthetic list entry is what makes this true.
            composeRule.onNodeWithText("Tasks").assertIsDisplayed()
        }
    }

    @Test
    fun `the create deep link opens the create screen above the list`() {
        launch(uri = "https://tajchert.github.io/tasks/new").use { scenario ->
            // "Create Task" is both the title and the submit button, so this
            // asserts on a label that appears exactly once on the screen.
            composeRule.onNodeWithText("Priority").assertIsDisplayed()

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            composeRule.waitForIdle()

            composeRule.onNodeWithText("Tasks").assertIsDisplayed()
        }
    }

    @Test
    fun `a uri this app does not own starts normally`() {
        // An unmatched link must not be a navigation primitive for whoever
        // sent it. The app opens where it always opens.
        launch(uri = "https://evil.test/tasks/abc-123").use {
            composeRule.onNodeWithText("Tasks").assertIsDisplayed()
        }
    }

    @Test
    fun `back on the root entry keeps the list on screen`() {
        launch(uri = null).use { scenario ->
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            composeRule.waitForIdle()

            // Popping the last entry would leave NavDisplay with an empty back
            // stack and nothing to render.
            composeRule.onNodeWithText("Tasks").assertIsDisplayed()
        }
    }

    @Test
    fun `a warm start deep link replaces the current back stack`() {
        // launchMode="singleTop" means a link arriving while the app is running
        // reaches onNewIntent, never onCreate. ActivityScenario cannot deliver a
        // new Intent to a running Activity, so this case drives the lifecycle
        // directly through Robolectric's controller.
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            composeRule.onNodeWithText("Tasks").assertIsDisplayed()

            controller.newIntent(deepLinkIntent("https://tajchert.github.io/tasks/new"))
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Priority").assertIsDisplayed()

            controller.newIntent(deepLinkIntent("https://tajchert.github.io/tasks/abc-123"))
            composeRule.waitForIdle()

            composeRule.onNodeWithText("Task Details").assertIsDisplayed()
            repeat(2) {
                composeRule.runOnUiThread {
                    controller.get().onBackPressedDispatcher.onBackPressed()
                }
                composeRule.waitForIdle()
                // First Back reaches list; second must stay there, without
                // revealing the previous create journey beneath it.
                composeRule.onNodeWithText("Tasks").assertIsDisplayed()
            }
        } finally {
            controller.close()
        }
    }

    @Test
    fun `a warm start uri this app does not own is ignored`() {
        // The same validation must run on the warm path. An unmatched link
        // leaves the user exactly where they were.
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            composeRule.onNodeWithText("Tasks").assertIsDisplayed()

            controller.newIntent(deepLinkIntent("https://evil.test/tasks/abc-123"))
            composeRule.waitForIdle()

            composeRule.onNodeWithText("Tasks").assertIsDisplayed()
        } finally {
            controller.close()
        }
    }

    @Test
    fun `the detail screen offers back navigation in a single pane window`() {
        launch(uri = "https://tajchert.github.io/tasks/abc-123").use {
            // The default Robolectric window is phone sized, so the list is not
            // visible alongside the detail and the arrow must be present.
            composeRule.onNodeWithContentDescription("Navigate back").assertIsDisplayed()
        }
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `a wide window shows the list beside the detail and drops the back arrow`() {
        // Same back stack, same entries, different window. This is the whole
        // claim of the adaptive layout, so it is asserted rather than assumed.
        launch(uri = "https://tajchert.github.io/tasks/abc-123").use {
            composeRule.onNodeWithText("Tasks").assertIsDisplayed()
            composeRule.onNodeWithText("Task Details").assertIsDisplayed()

            // The list never went away, so there is nothing to go back to.
            composeRule.onAllNodesWithContentDescription("Navigate back").assertCountEquals(0)
        }
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `a wide window without a selection shows the detail placeholder`() {
        launch(uri = null).use {
            composeRule.onNodeWithText("No task selected").assertIsDisplayed()
        }
    }

    private fun launch(uri: String?): ActivityScenario<MainActivity> =
        ActivityScenario.launch(
            if (uri == null) {
                Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            } else {
                deepLinkIntent(uri)
            },
        )

    private fun deepLinkIntent(uri: String): Intent = Intent(
        Intent.ACTION_VIEW,
        uri.toUri(),
        ApplicationProvider.getApplicationContext(),
        MainActivity::class.java,
    )
}
