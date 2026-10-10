package com.mtajchert.carom.app.feature.devices.impl

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mtajchert.carom.app.core.designsystem.theme.CaromTheme
import com.mtajchert.carom.app.core.model.DeviceKind
import com.mtajchert.carom.app.core.model.DeviceSummary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Compose behaviour on Robolectric: the screen renders and Back is wired. */
@RunWith(AndroidJUnit4::class)
class DevicesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var backs = 0
    @Suppress("unused")
    private val toggled = mutableListOf<Pair<Any, Boolean>>()

    @Test
    fun rendersAndNavigatesBack() {
        composeRule.setContent { CaromTheme { DevicesContent(state = DevicesUiState(loading = false, devices = listOf(DeviceSummary("dev_3xlhcrwNLeOI88IkAOPtCQ", "Pixel", DeviceKind.PHONE, "android", null, false, isSelf = true, trusted = true), DeviceSummary("dev_yEtJsCLemxZR0R2zF9V93Q", null, null, null, null, false, isSelf = false, trusted = false))), onBack = { backs++ }) } }
        composeRule.onNodeWithText("Pixel (this device)").assertIsDisplayed()
        composeRule.onNodeWithText("Unverified device yEtJsCLe").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Navigate back").performClick()
        assertEquals(1, backs)
    }
}
