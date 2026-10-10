package com.mtajchert.carom.app.feature.pairing.impl

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mtajchert.carom.app.core.data.sync.EnrollmentError
import com.mtajchert.carom.app.core.designsystem.theme.CaromTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Compose behaviour on Robolectric for every inviting-side state. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w412dp-h892dp")
class PairingScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val events = mutableListOf<String>()
    private var state: PairingUiState by mutableStateOf(PairingUiState.Creating)

    private fun show(initial: PairingUiState) {
        state = initial
        composeRule.setContent {
            CaromTheme {
                PairingContent(
                    state = state,
                    onBack = { events += "back" },
                    onCopy = { events += "copy:$it" },
                    onApprove = { events += "approve" },
                    onReject = { events += "reject" },
                    onNewCode = { events += "new" },
                    onDone = { events += "done" },
                )
            }
        }
    }

    @Test
    fun `the code screen shows the QR code, the countdown and copies the code`() {
        show(PairingUiState.ShowingCode(URI, secondsLeft = 272))
        composeRule.onNodeWithText("Add a device").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("QR code for pairing a new device").assertIsDisplayed()
        composeRule.onNodeWithText("Expires in 4:32").assertIsDisplayed()
        composeRule.onNodeWithText("Copy code instead").performScrollTo().performClick()
        composeRule.onNodeWithText("Copied. Paste it on the other device.").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Navigate back").performClick()
        assertEquals(listOf("copy:$URI", "back"), events)
    }

    @Test
    fun `the confirmation shows the fingerprint and both answers`() {
        show(PairingUiState.Confirm("Xq3v9KpL"))
        composeRule.onNodeWithText("Add new device?").assertIsDisplayed()
        composeRule.onNodeWithText("Xq3v9KpL").assertIsDisplayed()
        composeRule.onNodeWithText("Add device").performClick()
        composeRule.onNodeWithText("Don't add").performClick()
        assertEquals(listOf("approve", "reject"), events)
    }

    @Test
    fun `while approving the answer cannot be changed`() {
        show(PairingUiState.Confirm("Xq3v9KpL", approving = true))
        composeRule.onNodeWithText("Don't add").assertIsNotEnabled()
    }

    @Test
    fun `failures name the cause and offer a new code`() {
        show(PairingUiState.Failed(EnrollmentError.VERIFICATION_FAILED))
        composeRule.onNodeWithText("This device failed the check").assertIsDisplayed()
        composeRule.onNodeWithText("Show a new code").performClick()
        state = PairingUiState.Failed(EnrollmentError.EXPIRED)
        composeRule.onNodeWithText("The code expired").assertIsDisplayed()
        composeRule.onNodeWithText("Close").performClick()
        assertEquals(listOf("new", "done"), events)
    }

    @Test
    fun `a successful pairing ends with Done`() {
        show(PairingUiState.Added("Xq3v9KpL"))
        composeRule.onNodeWithText("Device added").assertIsDisplayed()
        composeRule.onNodeWithText("Done").performClick()
        assertEquals(listOf("done"), events)
    }

    private companion object {
        const val URI = "carom://pair?v=1&h=https%3A%2F%2Fhub.example.com&c=AAAAAAAAAAAAAAAAAAAAAA&s=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }
}
