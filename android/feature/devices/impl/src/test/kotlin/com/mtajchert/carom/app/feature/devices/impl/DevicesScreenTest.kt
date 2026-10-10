package com.mtajchert.carom.app.feature.devices.impl

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mtajchert.carom.app.core.designsystem.theme.CaromTheme
import com.mtajchert.carom.app.core.domain.ReceiveMatrix
import com.mtajchert.carom.app.core.domain.ReceiveSender
import com.mtajchert.carom.app.core.model.DeviceKind
import com.mtajchert.carom.app.core.model.DeviceSummary
import com.mtajchert.carom.app.core.protocol.MessageTypes
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Compose behaviour on Robolectric: rendering, back, remove confirmation and the receive matrix. */
@RunWith(AndroidJUnit4::class)
class DevicesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val selfId = "dev_3xlhcrwNLeOI88IkAOPtCQ"
    private val macId = "dev_yEtJsCLemxZR0R2zF9V93Q"
    private val state = DevicesUiState(
        loading = false,
        devices = listOf(
            DeviceSummary(selfId, "Pixel", DeviceKind.PHONE, "android", null, true, isSelf = true, trusted = true),
            DeviceSummary(macId, "Mac", DeviceKind.DESKTOP, "macos", null, false, isSelf = false, trusted = true),
            DeviceSummary("dev_qqqqqqqqqqqqqqqqqqqqqq", null, null, null, null, false, isSelf = false, trusted = false),
        ),
        matrix = ReceiveMatrix(
            types = listOf(MessageTypes.CLIPBOARD_SET),
            senders = listOf(ReceiveSender(macId, "Mac")),
            allowed = mapOf(MessageTypes.CLIPBOARD_SET to setOf(macId)),
        ),
    )

    private var backs = 0
    private val removed = mutableListOf<String>()
    private val toggled = mutableListOf<Triple<String, String, Boolean>>()

    private fun show(s: DevicesUiState = state) = composeRule.setContent {
        CaromTheme {
            DevicesContent(
                state = s,
                actions = DevicesActions(
                    onBack = { backs++ },
                    onRemove = { removed += it },
                    onSetReceive = { type, sender, allow -> toggled += Triple(type, sender, allow) },
                ),
            )
        }
    }

    @Test
    fun rendersAndNavigatesBack() {
        show()
        composeRule.onNodeWithText("Pixel (this device)").assertIsDisplayed()
        composeRule.onNodeWithText("Unverified device qqqqqqqq").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Navigate back").performClick()
        assertEquals(1, backs)
    }

    @Test
    fun removingAsksFirst() {
        show()
        // The second "Remove" belongs to the Mac.
        composeRule.onAllNodesWithText("Remove")[1].performScrollTo().performClick()
        composeRule.onNodeWithText("Remove Mac?").assertIsDisplayed()
        assertEquals(emptyList<String>(), removed)
        composeRule.onAllNodesWithText("Remove").let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        assertEquals(listOf(macId), removed)
    }

    @Test
    fun matrixCellsToggle() {
        show()
        composeRule.onNodeWithText("From Mac").performScrollTo().assertIsOn().performClick()
        assertEquals(listOf(Triple(MessageTypes.CLIPBOARD_SET, macId, false)), toggled)
        composeRule.onNodeWithText("Clipboard").assertIsDisplayed()
    }

    @Test
    fun matrixStartsUnchecked() {
        show(state.copy(matrix = state.matrix.copy(allowed = emptyMap())))
        composeRule.onNodeWithText("From Mac").performScrollTo().assertIsOff()
    }
}
