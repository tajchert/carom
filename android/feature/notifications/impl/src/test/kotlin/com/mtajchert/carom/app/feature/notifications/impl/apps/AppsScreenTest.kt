package com.mtajchert.carom.app.feature.notifications.impl.apps

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mtajchert.carom.app.core.designsystem.theme.CaromTheme
import com.mtajchert.carom.app.core.model.AppForwardingMode
import com.mtajchert.carom.app.core.model.NotificationAppSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Compose behaviour on Robolectric: rows render, choices and switches call back. */
@RunWith(AndroidJUnit4::class)
class AppsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val modes = mutableListOf<Pair<String, AppForwardingMode>>()
    private val ongoing = mutableListOf<Pair<String, Boolean>>()
    private val queries = mutableListOf<String>()
    private val filters = mutableListOf<AppsFilter>()
    private val toggled = mutableListOf<String>()
    private val bulk = mutableListOf<AppForwardingMode>()
    private var cleared = 0
    private var accessClicks = 0
    private var backs = 0

    private fun show(state: AppsUiState) = composeRule.setContent {
        CaromTheme {
            AppsContent(
                state = state,
                onBack = { backs++ },
                onQueryChange = { queries += it },
                onFilterChange = { filters += it },
                onModeChange = { pkg, mode -> modes += pkg to mode },
                onForwardOngoingChange = { pkg, on -> ongoing += pkg to on },
                onOpenAccessSettings = { accessClicks++ },
                onToggleSelected = { toggled += it },
                onClearSelection = { cleared++ },
                onModeForSelected = { bulk += it },
            )
        }
    }

    @Test
    fun appsAndChoices() {
        show(
            AppsUiState(
                loading = false,
                apps = listOf(NotificationAppSetting("org.signal", "Signal", AppForwardingMode.HIDE_CONTENT, 0)),
                totalApps = 1,
            ),
        )
        composeRule.onNodeWithText("Signal").assertIsDisplayed()
        composeRule.onNodeWithText("org.signal").assertIsDisplayed()
        composeRule.onNodeWithText("Hide").assertIsDisplayed()
        composeRule.onNodeWithText("Signal").performClick()
        composeRule.onNodeWithText("Hide content").assertIsSelected()
        composeRule.onNodeWithText("Off").performClick()
        assertEquals(listOf("org.signal" to AppForwardingMode.OFF), modes)

        composeRule.onNodeWithText("Search apps").performTextInput("sig")
        assertEquals(listOf("sig"), queries)

        composeRule.onNodeWithContentDescription("Navigate back").performClick()
        assertEquals(1, backs)
    }

    @Test
    fun longPressSelectsAndTheBarAppliesOneMode() {
        val apps = listOf(
            NotificationAppSetting("org.signal", "Signal", AppForwardingMode.FORWARD, 0),
            NotificationAppSetting("com.bank", "My Bank", AppForwardingMode.HIDE_CONTENT, 0),
        )
        var state by mutableStateOf(AppsUiState(loading = false, apps = apps, totalApps = 2))
        composeRule.setContent {
            CaromTheme {
                AppsContent(
                    state = state,
                    onBack = {},
                    onQueryChange = {},
                    onFilterChange = {},
                    onModeChange = { pkg, mode -> modes += pkg to mode },
                    onForwardOngoingChange = { _, _ -> },
                    onOpenAccessSettings = {},
                    onToggleSelected = { pkg ->
                        toggled += pkg
                        state = state.copy(selected = if (pkg in state.selected) state.selected - pkg else state.selected + pkg)
                    },
                    onClearSelection = { cleared++ },
                    onModeForSelected = { bulk += it },
                )
            }
        }
        composeRule.onNodeWithText("Signal").performTouchInput { longClick() }
        composeRule.onNodeWithText("1 selected").assertIsDisplayed()
        // In selection mode a tap toggles instead of expanding.
        composeRule.onNodeWithText("My Bank").performClick()
        composeRule.onNodeWithText("2 selected").assertIsDisplayed()
        assertEquals(listOf("org.signal", "com.bank"), toggled)
        composeRule.onNodeWithText("Hide content").performClick()
        assertEquals(listOf(AppForwardingMode.HIDE_CONTENT), bulk)
        assertTrue(modes.isEmpty())
        composeRule.onNodeWithContentDescription("Clear selection").performClick()
        assertEquals(1, cleared)
    }

    @Test
    fun ongoingSwitch() {
        show(
            AppsUiState(
                loading = false,
                apps = listOf(
                    NotificationAppSetting("org.signal", "Signal", AppForwardingMode.FORWARD, 0),
                    NotificationAppSetting("com.nav", "Maps", AppForwardingMode.FORWARD, 0, forwardOngoing = true),
                ),
                totalApps = 2,
            ),
        )
        composeRule.onNodeWithText("Signal").performClick()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Ongoing: Off"))
        composeRule.onNodeWithText("Ongoing: Off").assertIsDisplayed().performClick()
        assertEquals(listOf("org.signal" to true), ongoing)
        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Maps"))
        composeRule.onNodeWithText("Maps").performClick()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Ongoing: Forward"))
        composeRule.onNodeWithText("Ongoing: Forward").assertIsDisplayed().performClick()
        assertEquals(listOf("org.signal" to true, "com.nav" to false), ongoing)
    }

    @Test
    fun filterChipsShowCountsAndSelect() {
        show(
            AppsUiState(
                loading = false,
                apps = listOf(NotificationAppSetting("org.signal", "Signal", AppForwardingMode.FORWARD, 0)),
                totalApps = 3,
                forwardCount = 1,
                hiddenCount = 1,
                offCount = 1,
            ),
        )
        composeRule.onNodeWithText("All 3").assertIsDisplayed()
        composeRule.onNodeWithText("Forward 1").assertIsDisplayed()
        composeRule.onNodeWithText("Hidden 1").performClick()
        assertEquals(listOf(AppsFilter.HIDDEN), filters)
    }

    @Test
    fun emptyListAndMissingAccess() {
        show(AppsUiState(loading = false, accessGranted = false))
        composeRule.onNodeWithText("No apps yet").assertIsDisplayed()
        composeRule.onAllNodesWithText("Search apps").assertCountEquals(0)
        composeRule.onNodeWithText("Allow notification access").performClick()
        assertEquals(1, accessClicks)
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals(n: Int) {
    assertEquals(n, fetchSemanticsNodes().size)
}
