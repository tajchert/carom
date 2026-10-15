package com.mtajchert.carom.app.feature.settings.impl

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mtajchert.carom.app.core.designsystem.theme.CaromTheme
import com.mtajchert.carom.app.core.model.ActionsFromMac
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Compose behaviour on Robolectric: rendering, toggles, navigation entries and leaving. */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val paired = SettingsUiState(
        loading = false,
        paired = true,
        deviceName = "Pixel",
        hubUrl = "https://hub.example.com",
        deviceId = "dev_3xlhcrwNLeOI88IkAOPtCQ",
        appVersion = "1.0.0",
    )

    private var backs = 0
    private val paused = mutableListOf<Boolean>()
    private val onlyLocked = mutableListOf<Boolean>()
    private var apps = 0
    private var left = 0
    private val modes = mutableListOf<ActionsFromMac>()
    private val windows = mutableListOf<Int>()

    private fun show(state: SettingsUiState, withApps: Boolean = true) = composeRule.setContent {
        CaromTheme {
            SettingsContent(
                state = state,
                actions = SettingsActions(
                    onBack = { backs++ },
                    onOpenNotificationApps = if (withApps) ({ apps++ }) else null,
                    onPauseChanged = { paused += it },
                    onOnlyWhenLockedChanged = { onlyLocked += it },
                    onLeaveHub = { left++ },
                    onActionsFromMacChanged = { modes += it },
                    onActionWindowChanged = { windows += it },
                ),
            )
        }
    }

    @Test
    fun rendersAndNavigatesBack() {
        show(paired)
        composeRule.onNodeWithText("Pause notification forwarding").assertIsDisplayed().performClick()
        assertEquals(listOf(true), paused)
        composeRule.onNodeWithText("Only when screen is locked or off").performScrollTo().performClick()
        assertEquals(listOf(true), onlyLocked)
        composeRule.onNodeWithText("Apps").performClick()
        assertEquals(1, apps)
        composeRule.onNodeWithContentDescription("Navigate back").performClick()
        assertEquals(1, backs)
    }

    @Test
    fun showsHubAndDeviceId() {
        show(paired)
        composeRule.onNodeWithText("https://hub.example.com").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("dev_3xlhcrwNLeOI88IkAOPtCQ").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun leavingAsksFirst() {
        show(paired)
        composeRule.onNodeWithText("Leave hub").performScrollTo().performClick()
        composeRule.onNodeWithText("Leave your Hub?").assertIsDisplayed()
        assertEquals(0, left)
        composeRule.onNodeWithText("Leave").performClick()
        assertEquals(1, left)
    }

    @Test
    fun entriesWithoutDestinationsAreHidden() {
        show(paired.copy(paired = false, hubUrl = null, deviceId = null), withApps = false)
        assertTrue(composeRule.onAllNodesWithTextCount("Apps") == 0)
        assertTrue(composeRule.onAllNodesWithTextCount("Leave hub") == 0)
    }

    @Test
    fun choosingAnActionsModeReportsIt() {
        show(paired)
        composeRule.onNodeWithText("Actions from Mac").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Always while a Mac is online").performScrollTo().performClick()
        composeRule.onNodeWithText("Off").performScrollTo().performClick()
        assertEquals(listOf(ActionsFromMac.ALWAYS, ActionsFromMac.OFF), modes)
    }

    @Test
    fun windowLengthsAreOfferedAfterANotification() {
        show(paired.copy(actionsFromMac = ActionsFromMac.AFTER_NOTIFICATION, actionWindowMinutes = 5))
        composeRule.onNodeWithText("15 min").performScrollTo().performClick()
        assertEquals(listOf(15), windows)
    }

    @Test
    fun windowLengthsAreHiddenForOtherModes() {
        show(paired.copy(actionsFromMac = ActionsFromMac.ALWAYS))
        assertTrue(composeRule.onAllNodesWithTextCount("15 min") == 0)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}
