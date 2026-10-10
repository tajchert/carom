package com.mtajchert.carom.app.feature.notifications.impl.apps

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mtajchert.carom.app.core.designsystem.theme.CaromTheme
import com.mtajchert.carom.app.core.model.AppForwardingMode
import com.mtajchert.carom.app.core.model.NotificationAppSetting
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Compose behaviour on Robolectric: rows render, choices and switches call back. */
@RunWith(AndroidJUnit4::class)
class AppsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val modes = mutableListOf<Pair<String, AppForwardingMode>>()
    private val queries = mutableListOf<String>()
    private var paused: Boolean? = null
    private var accessClicks = 0
    private var backs = 0

    private fun show(state: AppsUiState) = composeRule.setContent {
        CaromTheme {
            AppsContent(
                state = state,
                onBack = { backs++ },
                onQueryChange = { queries += it },
                onModeChange = { pkg, mode -> modes += pkg to mode },
                onPausedChange = { paused = it },
                onOnlyWhenLockedChange = {},
                onOpenAccessSettings = { accessClicks++ },
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
        composeRule.onNodeWithText("Hide content").assertIsSelected()
        composeRule.onNodeWithText("Off").performClick()
        assertEquals(listOf("org.signal" to AppForwardingMode.OFF), modes)

        composeRule.onNodeWithText("Pause forwarding").performClick()
        assertEquals(true, paused)

        composeRule.onNodeWithText("Search apps").performTextInput("sig")
        assertEquals(listOf("sig"), queries)

        composeRule.onNodeWithContentDescription("Navigate back").performClick()
        assertEquals(1, backs)
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
