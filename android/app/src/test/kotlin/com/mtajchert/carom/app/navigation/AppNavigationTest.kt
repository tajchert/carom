package com.mtajchert.carom.app.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        composeRule.onNodeWithText("Welcome to Carom").assertIsDisplayed()
        composeRule.onNodeWithText("Get started").performClick()
        composeRule.onNodeWithText("Connect to your Hub").assertIsDisplayed()
        back()
        composeRule.onNodeWithText("Welcome to Carom").assertIsDisplayed()
        back()
        composeRule.onNodeWithText("Welcome to Carom").assertIsDisplayed()
    }

    @Test
    fun `Home offers setup and opens onboarding`() {
        startOnHome()
        composeRule.onNodeWithText("Connect this phone").assertIsDisplayed()
        composeRule.onNodeWithText("Set up Carom").performClick()
        composeRule.onNodeWithText("Welcome to Carom").assertIsDisplayed()
        back()
        composeRule.onNodeWithText("Connect this phone").assertIsDisplayed()
    }

    @Test
    fun `settings open from Home and show the toggles`() {
        startOnHome()
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.onNodeWithText("Pause notification forwarding").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Navigate back").performClick()
        composeRule.onNodeWithText("Connect this phone").assertIsDisplayed()
    }

    @Test
    fun `back on Home keeps Home on screen`() {
        startOnHome()
        back()
        composeRule.onNodeWithText("Connect this phone").assertIsDisplayed()
    }

    /** Replaces the activity's content with a nav host that starts on Home, as for a paired device. */
    private fun startOnHome() {
        composeRule.runOnUiThread {
            composeRule.activity.setContent { CaromTheme { CaromNavHost(needsOnboarding = false) } }
        }
        // Home loads the (empty) session first.
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("Connect this phone").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun back() {
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }
}
