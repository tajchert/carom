package com.mtajchert.carom.app.feature.settings.impl

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mtajchert.carom.app.core.designsystem.theme.CaromTheme
import com.mtajchert.carom.app.core.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Compose behaviour on Robolectric: the screen renders and Back is wired. */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var backs = 0
    @Suppress("unused")
    private val toggled = mutableListOf<Pair<Any, Boolean>>()

    @Test
    fun rendersAndNavigatesBack() {
        composeRule.setContent { CaromTheme { SettingsContent(prefs = UserPreferences(), onBack = { backs++ }, onToggle = { t, v -> toggled += t to v }) } }
        composeRule.onNodeWithText("Pause notification forwarding").assertIsDisplayed()
        composeRule.onNodeWithText("Pause notification forwarding").performClick()
        assertEquals(listOf<Pair<Any, Boolean>>(SettingToggle.PAUSE_FORWARDING to true), toggled)
        composeRule.onNodeWithContentDescription("Navigate back").performClick()
        assertEquals(1, backs)
    }
}
