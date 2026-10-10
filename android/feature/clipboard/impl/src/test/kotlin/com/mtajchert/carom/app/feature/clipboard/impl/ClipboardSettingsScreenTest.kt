package com.mtajchert.carom.app.feature.clipboard.impl

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
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

@RunWith(AndroidJUnit4::class)
class ClipboardSettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()
    private val state = ClipboardSettingsState(
        sendTo = listOf(ClipboardDeviceRow("dev_mac", "Work Mac", true), ClipboardDeviceRow("dev_pc", "Home PC", false)),
        receiveFromAll = false,
        receiveFrom = listOf(ClipboardDeviceRow("dev_mac", "Work Mac", true), ClipboardDeviceRow("dev_pc", "Home PC", false)),
        loaded = true,
    )

    private fun show(s: ClipboardSettingsState = state, back: () -> Unit = {}) = composeRule.setContent {
        CaromTheme {
            ClipboardSettingsContent(
                state = s,
                onBack = back,
                onSendNow = { events += "send" },
                onSendTo = { id, on -> events += "sendTo $id $on" },
                onReceiveFromAll = { events += "all $it" },
                onReceiveFrom = { id, on -> events += "from $id $on" },
                onArrivalNotification = { events += "notify $it" },
            )
        }
    }

    @Test
    fun `renders both device lists and forwards every interaction`() {
        var backs = 0
        show(back = { backs++ })

        composeRule.onNodeWithText("Send clipboard to").assertIsDisplayed()
        composeRule.onNodeWithText("Receive clipboard from").assertIsDisplayed()
        composeRule.onNodeWithText("Send clipboard").performClick()
        composeRule.onNodeWithText("Show a notification when a clipboard arrives").performClick()
        composeRule.onNodeWithText("Any device").performClick()
        composeRule.onNodeWithContentDescription("Navigate back").performClick()

        assertEquals(listOf("send", "notify false", "all true"), events)
        assertEquals(1, backs)
    }

    @Test
    fun `toggling a device in each list reports its id`() {
        show()

        // Both lists contain both devices; "Send clipboard to" comes first.
        composeRule.onAllNodesWithText("Home PC")[0].performClick()
        composeRule.onAllNodesWithText("Work Mac")[1].performClick()

        assertEquals(listOf("sendTo dev_pc true", "from dev_mac false"), events)
    }

    @Test
    fun `the receive list is hidden while any device may send`() {
        show(state.copy(receiveFromAll = true))

        assertEquals(1, composeRule.onAllNodesWithText("Home PC").fetchSemanticsNodes().size)
    }
}
