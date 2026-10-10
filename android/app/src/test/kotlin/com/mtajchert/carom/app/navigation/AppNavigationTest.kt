package com.mtajchert.carom.app.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mtajchert.carom.app.MainActivity
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
 * keys, so Home offers setup; every destination is reachable and Back returns to Home.
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
    fun `a fresh install starts on Home and offers setup`() {
        composeRule.onNodeWithText("Connect this phone").assertIsDisplayed()
        composeRule.onNodeWithText("Set up Carom").performClick()
        composeRule.onNodeWithText("Welcome to Carom").assertIsDisplayed()
        back()
        composeRule.onNodeWithText("Connect this phone").assertIsDisplayed()
    }

    @Test
    fun `settings open from Home and show the toggles`() {
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.onNodeWithText("Pause notification forwarding").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Navigate back").performClick()
        composeRule.onNodeWithText("Connect this phone").assertIsDisplayed()
    }

    @Test
    fun `back on Home keeps Home on screen`() {
        back()
        composeRule.onNodeWithText("Connect this phone").assertIsDisplayed()
    }

    private fun back() {
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }
}
