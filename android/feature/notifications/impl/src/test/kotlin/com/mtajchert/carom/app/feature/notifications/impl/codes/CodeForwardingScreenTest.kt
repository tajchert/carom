package com.mtajchert.carom.app.feature.notifications.impl.codes

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

@RunWith(AndroidJUnit4::class)
class CodeForwardingScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()
    private val command = AdbFallback.command("com.mtajchert.carom.app")

    private fun show(ui: CodeForwardingUi) = composeRule.setContent {
        CaromTheme {
            CodeForwardingContent(
                ui = ui,
                onEnable = { events += "enable" },
                onCopyCommand = { events += "copy:$it" },
                onClose = { events += "close" },
            )
        }
    }

    @Test
    fun `the intro explains the step and starts it`() {
        show(CodeForwardingUi.Intro)
        composeRule.onNodeWithText("Android 15 and later hides verification codes", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Enable code forwarding").performClick()
        assertEquals(listOf("enable"), events)
    }

    @Test
    fun `the adb screen shows the exact command and copies it`() {
        show(CodeForwardingUi.UseAdb(command))
        composeRule.onNodeWithText(command).assertIsDisplayed()
        composeRule.onNodeWithText("Copy command").performClick()
        composeRule.onNodeWithText("Done").performClick()
        assertEquals(listOf("copy:$command", "close"), events)
    }

    @Test
    fun `enabled and unsupported close the screen`() {
        show(CodeForwardingUi.Enabled)
        composeRule.onNodeWithText("Code forwarding is on.").assertIsDisplayed()
        composeRule.onNodeWithText("Done").performClick()
        assertEquals(listOf("close"), events)
    }

    @Test
    fun `working shows progress text and the back arrow closes`() {
        show(CodeForwardingUi.Working)
        composeRule.onNodeWithText("Waiting for Android…").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Navigate back").performClick()
        assertEquals(listOf("close"), events)
    }

    @Test
    fun `the access contract points at the activity class`() {
        assertEquals(CodeForwardingActivity::class.java.name, AndroidCodeForwardingAccess.ACTIVITY_CLASS)
    }
}
