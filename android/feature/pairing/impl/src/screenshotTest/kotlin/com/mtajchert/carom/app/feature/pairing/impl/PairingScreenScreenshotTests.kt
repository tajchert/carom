package com.mtajchert.carom.app.feature.pairing.impl

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.mtajchert.carom.app.core.data.sync.EnrollmentError
import com.mtajchert.carom.app.core.designsystem.preview.FormFactorPreviews
import com.mtajchert.carom.app.core.designsystem.preview.LargeFontPreview
import com.mtajchert.carom.app.core.designsystem.theme.CaromTheme

/** A fixed, made-up pair URI so the QR code renders the same every time. */
private const val URI =
    "carom://pair?v=1&h=https%3A%2F%2Fcarom-hub.example.workers.dev&c=q2Lk8vN3pXa9Rt5mZc7YbA&s=0aF3kQ9zL2xV7mN1pR6tY8wB4cD5eG0hJ2kL3mN4pQ5"

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

@Composable
private fun Pairing(state: PairingUiState) {
    CaromTheme(dynamicColor = false) {
        PairingContent(state = state, onBack = {}, onCopy = {}, onApprove = {}, onReject = {}, onNewCode = {}, onDone = {})
    }
}

@PreviewTest
@PhoneThemePreviews
@Composable
fun PairingCreatingScreenshotTest() = Pairing(PairingUiState.Creating)

@PreviewTest
@PhoneThemePreviews
@Composable
fun PairingCodeScreenshotTest() = Pairing(PairingUiState.ShowingCode(URI, secondsLeft = 287))

@PreviewTest
@FormFactorPreviews
@Composable
fun PairingCodeFormFactorsScreenshotTest() = Pairing(PairingUiState.ShowingCode(URI, secondsLeft = 287))

@PreviewTest
@PhoneThemePreviews
@Composable
fun PairingConfirmScreenshotTest() = Pairing(PairingUiState.Confirm("Xq3v9KpL"))

@PreviewTest
@LargeFontPreview
@Composable
fun PairingConfirmLargeFontScreenshotTest() = Pairing(PairingUiState.Confirm("Xq3v9KpL", approving = true))

@PreviewTest
@PhoneThemePreviews
@Composable
fun PairingAddedScreenshotTest() = Pairing(PairingUiState.Added("Xq3v9KpL"))

@PreviewTest
@PhoneThemePreviews
@Composable
fun PairingDeclinedScreenshotTest() = Pairing(PairingUiState.Declined)

@PreviewTest
@PhoneThemePreviews
@Composable
fun PairingExpiredScreenshotTest() = Pairing(PairingUiState.Failed(EnrollmentError.EXPIRED))

@PreviewTest
@PhoneThemePreviews
@Composable
fun PairingSuspiciousScreenshotTest() = Pairing(PairingUiState.Failed(EnrollmentError.VERIFICATION_FAILED))

@PreviewTest
@Preview(name = "Phone", showBackground = true, widthDp = 412, heightDp = 892, apiLevel = 36)
@Composable
fun PairingUnreachableScreenshotTest() = Pairing(PairingUiState.Failed(EnrollmentError.UNREACHABLE))
