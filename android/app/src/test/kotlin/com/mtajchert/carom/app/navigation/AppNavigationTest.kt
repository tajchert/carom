package com.mtajchert.carom.app.navigation

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.activity.compose.setContent
import com.mtajchert.carom.app.MainActivity
import com.mtajchert.carom.app.core.designsystem.theme.CaromTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Launches the real MainActivity with the real Hilt graph on Robolectric. A fresh install has no
 * keys, so it starts in onboarding. Home-based tests swap in a nav host that starts on Home;
 * every destination is reachable and Back returns to Home.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class)
class AppNavigationTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun `a fresh install starts in onboarding and Back walks its steps`() {
        awaitText("Welcome to Carom").assertIsDisplayed()
        awaitText("Get started").performClick()
        awaitText("Connect to your Hub").assertIsDisplayed()
        back()
        awaitText("Welcome to Carom").assertIsDisplayed()
        back()
        awaitText("Welcome to Carom").assertIsDisplayed()
    }

    @Test
    fun `Home offers setup and opens onboarding`() {
        startOnHome()
        awaitText("Connect this phone").assertIsDisplayed()
        awaitText("Set up Carom").performClick()
        awaitText("Welcome to Carom").assertIsDisplayed()
        back()
        awaitText("Connect this phone").assertIsDisplayed()
    }

    @Test
    fun `settings open from Home and show the toggles`() {
        startOnHome()
        composeRule.onNodeWithContentDescription("Settings").performClick()
        awaitText("Pause notification forwarding").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Navigate back").performClick()
        awaitText("Connect this phone").assertIsDisplayed()
    }

    @Test
    fun `settings open the Apps and Clipboard screens with the settings moved there`() {
        startOnHome()
        composeRule.onNodeWithContentDescription("Settings").performClick()
        awaitText("Apps").performScrollTo().performClick()
        awaitText("Only when screen is locked or off").performScrollTo().assertIsDisplayed()
        back()
        awaitText("Clipboard").performScrollTo().performClick()
        awaitText("Show a notification when a clipboard arrives").performScrollTo().assertIsDisplayed()
        back()
        awaitText("Pause notification forwarding").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `back on Home keeps Home on screen`() {
        startOnHome()
        back()
        awaitText("Connect this phone").assertIsDisplayed()
    }

    /** Replaces the activity's content with a nav host that starts on Home, as for a paired device. */
    private fun startOnHome() {
        composeRule.runOnUiThread {
            composeRule.activity.setContent { CaromTheme { CaromNavHost(needsOnboarding = false) } }
        }
        // Home loads the (empty) session first.
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Connect this phone").fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * Waits until exactly one node shows [text]. Screens load their state and animate in, and the
     * outgoing screen may still be on screen.
     */
    private fun awaitText(text: String): SemanticsNodeInteraction {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().size == 1
        }
        return composeRule.onNodeWithText(text, useUnmergedTree = true)
    }

    private fun back() {
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }
}
