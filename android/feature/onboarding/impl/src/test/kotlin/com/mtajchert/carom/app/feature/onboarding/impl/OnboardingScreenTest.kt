package com.mtajchert.carom.app.feature.onboarding.impl

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mtajchert.carom.app.core.designsystem.theme.CaromTheme

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Compose behaviour on Robolectric: the screen renders and Back is wired. */
@RunWith(AndroidJUnit4::class)
class OnboardingScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var backs = 0
    @Suppress("unused")
    private val toggled = mutableListOf<Pair<Any, Boolean>>()

    @Test
    fun rendersAndNavigatesBack() {
        composeRule.setContent { CaromTheme { OnboardingScreen(onBack = { backs++ }) } }
        composeRule.onNodeWithText("Welcome to Carom").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Navigate back").performClick()
        assertEquals(1, backs)
    }
}
