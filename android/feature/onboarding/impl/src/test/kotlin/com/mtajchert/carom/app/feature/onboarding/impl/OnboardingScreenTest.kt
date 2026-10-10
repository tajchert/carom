package com.mtajchert.carom.app.feature.onboarding.impl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mtajchert.carom.app.core.data.sync.Clock
import com.mtajchert.carom.app.core.designsystem.theme.CaromTheme
import com.mtajchert.carom.app.core.testing.hub.FakeHub
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Compose behaviour on Robolectric: the whole setup flow against an in-memory Hub. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w412dp-h892dp")
class OnboardingScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val hub = FakeHub()
    private val device = TestDevice(hub, Clock.SYSTEM)
    private val events = mutableListOf<String>()

    @Composable
    private fun Harness(vm: OnboardingViewModel, permissions: PermissionStatus) {
        val step by vm.step.collectAsState()
        CaromTheme {
            OnboardingContent(
                step = step,
                actions = OnboardingActions(
                    onBack = { if (vm.step.value.canGoBack()) vm.back() else events += "leave" },
                    onStart = vm::start,
                    onChoose = vm::choose,
                    onAllowCamera = { events += "camera" },
                    onInputChange = vm::onInputChange,
                    onSubmit = vm::submit,
                    onRetry = vm::retry,
                    onNameChange = vm::onNameChange,
                    onSaveName = vm::saveName,
                    onOpenNotificationAccess = { events += "listener" },
                    onOpenAppInfo = { events += "appInfo" },
                    onRequestPostNotifications = { events += "post" },
                    onOpenBatterySettings = { events += "battery" },
                    onPermissionsDone = vm::permissionsDone,
                    onFinish = { events += "finish" },
                ),
                camera = CameraAccess.NEEDS_PERMISSION,
                permissions = permissions,
            )
        }
    }

    @Test
    fun `a new user sets up a hub by pasting the code and finishes on Home`() {
        val vm = device.onboarding(defaultName = "Pixel 9")
        composeRule.setContent { Harness(vm, PermissionStatus(false, false, false, showRestrictedSettingsHint = true)) }

        composeRule.onNodeWithText("Welcome to Carom").assertIsDisplayed()
        composeRule.onNodeWithText("Get started").performClick()
        composeRule.onNodeWithText("Connect to your Hub").assertIsDisplayed()
        composeRule.onNodeWithText("Set up a new hub").performClick()

        composeRule.onNodeWithText("Scan your setup code").assertIsDisplayed()
        composeRule.onNodeWithText("Allow camera").performClick()
        assertEquals(listOf("camera"), events)

        val field = composeRule.onNode(hasSetTextAction() and hasText("Code"))
        field.performScrollTo().performTextInput("not a code")
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.onNodeWithText("That isn't a Carom code. Use the code that starts with carom://.").performScrollTo().assertIsDisplayed()

        field.performTextReplacement(hub.setupUri())
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitUntil(5_000) { vm.step.value is OnboardingStep.Name }

        composeRule.onNodeWithText("Name this device").assertIsDisplayed()
        val name = composeRule.onNode(hasSetTextAction())
        name.assertTextContains("Pixel 9")
        name.performTextReplacement("Work phone")
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitUntil(5_000) { vm.step.value == OnboardingStep.Permissions }

        composeRule.onNodeWithText("Allow access").assertIsDisplayed()
        composeRule.onNodeWithText("Open App info").performScrollTo().performClick()
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.onNodeWithText("You're all set").assertIsDisplayed()
        composeRule.onNodeWithText("Open Carom").performClick()

        assertEquals(listOf("camera", "appInfo", "finish"), events)
        assertEquals("Work phone", device.preferences.current.deviceName)
    }

    @Test
    fun `the back arrow steps back and is hidden on the welcome`() {
        val vm = device.onboarding()
        composeRule.setContent { Harness(vm, PermissionStatus(false, null, false, false)) }
        composeRule.onNodeWithContentDescription("Navigate back").assertDoesNotExist()
        composeRule.onNodeWithText("Get started").performClick()
        composeRule.onNodeWithText("Join with a code").performClick()
        composeRule.onNodeWithText("Scan the code on your other device").assertIsDisplayed()
        composeRule.onNodeWithText("Continue").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Navigate back").performClick()
        composeRule.onNodeWithContentDescription("Navigate back").performClick()
        composeRule.onNodeWithText("Welcome to Carom").assertIsDisplayed()
    }

    @Test
    fun `permissions show what is allowed and open the right settings`() {
        composeRule.setContent {
            CaromTheme {
                OnboardingContent(
                    step = OnboardingStep.Permissions,
                    actions = OnboardingActions(
                        onOpenBatterySettings = { events += "battery" },
                        onRequestPostNotifications = { events += "post" },
                    ),
                    camera = CameraAccess.GRANTED,
                    permissions = PermissionStatus(notificationAccess = true, postNotifications = false, batteryUnrestricted = false, showRestrictedSettingsHint = false),
                )
            }
        }
        composeRule.onNodeWithText("Allowed").assertIsDisplayed()
        composeRule.onNodeWithText("Allow").performScrollTo().performClick()
        composeRule.onNodeWithText("Open settings").performScrollTo().performClick()
        assertEquals(listOf("post", "battery"), events)
    }
}
