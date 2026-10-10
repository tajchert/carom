package com.mtajchert.carom.app.feature.onboarding.impl

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.mtajchert.carom.app.core.data.sync.EnrollmentError
import com.mtajchert.carom.app.core.designsystem.preview.FormFactorPreviews
import com.mtajchert.carom.app.core.designsystem.preview.LargeFontPreview
import com.mtajchert.carom.app.core.designsystem.theme.CaromTheme

@Preview(name = "Light", showBackground = true, widthDp = 412, heightDp = 892, apiLevel = 36)
@Preview(
    name = "Dark",
    showBackground = true,
    widthDp = 412,
    heightDp = 892,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    apiLevel = 36,
)
internal annotation class PhoneThemePreviews

@Preview(name = "Phone", showBackground = true, widthDp = 412, heightDp = 892, apiLevel = 36)
internal annotation class PhonePreview

private val missing = PermissionStatus(notificationAccess = false, postNotifications = false, batteryUnrestricted = false, showRestrictedSettingsHint = true)

@Composable
private fun Onboarding(
    step: OnboardingStep,
    camera: CameraAccess = CameraAccess.NEEDS_PERMISSION,
    permissions: PermissionStatus = missing,
) {
    CaromTheme(dynamicColor = false) {
        OnboardingContent(
            step = step,
            actions = OnboardingActions(),
            camera = camera,
            permissions = permissions,
            // Stand-in for the live camera picture.
            cameraPreview = { modifier ->
                Box(modifier.background(Brush.linearGradient(listOf(Color(0xFF3C4A52), Color(0xFF1C2328)))))
            },
        )
    }
}

@PreviewTest
@PhoneThemePreviews
@Composable
fun WelcomeScreenshotTest() = Onboarding(OnboardingStep.Welcome)

@PreviewTest
@PhoneThemePreviews
@Composable
fun ConnectScreenshotTest() = Onboarding(OnboardingStep.Connect)

@PreviewTest
@FormFactorPreviews
@Composable
fun ConnectFormFactorsScreenshotTest() = Onboarding(OnboardingStep.Connect)

@PreviewTest
@PhoneThemePreviews
@Composable
fun ScanNeedsCameraScreenshotTest() = Onboarding(OnboardingStep.Scan(ConnectMode.SETUP))

@PreviewTest
@PhoneThemePreviews
@Composable
fun ScanCameraScreenshotTest() = Onboarding(OnboardingStep.Scan(ConnectMode.JOIN), camera = CameraAccess.GRANTED)

@PreviewTest
@PhonePreview
@Composable
fun ScanInvalidCodeScreenshotTest() = Onboarding(
    OnboardingStep.Scan(ConnectMode.JOIN, input = "https://example.com", error = CodeError.INVALID),
    camera = CameraAccess.DENIED,
)

@PreviewTest
@PhonePreview
@Composable
fun WorkingScreenshotTest() = Onboarding(OnboardingStep.Working(ConnectMode.SETUP))

@PreviewTest
@PhoneThemePreviews
@Composable
fun AwaitingApprovalScreenshotTest() = Onboarding(OnboardingStep.AwaitingApproval("Xq3v9KpL"))

@PreviewTest
@PhonePreview
@Composable
fun FailedExpiredScreenshotTest() = Onboarding(OnboardingStep.Failed(ConnectMode.JOIN, EnrollmentError.EXPIRED))

@PreviewTest
@PhoneThemePreviews
@Composable
fun FailedAlreadySetUpScreenshotTest() = Onboarding(OnboardingStep.Failed(ConnectMode.SETUP, EnrollmentError.ALREADY_SET_UP))

@PreviewTest
@PhoneThemePreviews
@Composable
fun NameScreenshotTest() = Onboarding(OnboardingStep.Name("Pixel 9 Pro"))

@PreviewTest
@PhoneThemePreviews
@Composable
fun PermissionsScreenshotTest() = Onboarding(OnboardingStep.Permissions)

@PreviewTest
@PhonePreview
@Composable
fun PermissionsGrantedScreenshotTest() = Onboarding(
    OnboardingStep.Permissions,
    permissions = PermissionStatus(notificationAccess = true, postNotifications = true, batteryUnrestricted = false, showRestrictedSettingsHint = false),
)

@PreviewTest
@LargeFontPreview
@Composable
fun PermissionsLargeFontScreenshotTest() = Onboarding(OnboardingStep.Permissions)

@PreviewTest
@PhoneThemePreviews
@Composable
fun DoneScreenshotTest() = Onboarding(OnboardingStep.Done)
