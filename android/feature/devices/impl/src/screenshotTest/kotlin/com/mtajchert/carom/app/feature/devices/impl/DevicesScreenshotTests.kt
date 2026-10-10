package com.mtajchert.carom.app.feature.devices.impl

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.mtajchert.carom.app.core.designsystem.preview.FormFactorPreviews
import com.mtajchert.carom.app.core.designsystem.preview.LargeFontPreview
import com.mtajchert.carom.app.core.designsystem.theme.CaromTheme
import com.mtajchert.carom.app.core.domain.ReceiveMatrix
import com.mtajchert.carom.app.core.domain.ReceiveSender
import com.mtajchert.carom.app.core.model.DeviceKind
import com.mtajchert.carom.app.core.model.DeviceSummary
import com.mtajchert.carom.app.core.protocol.MessageTypes

private const val NOW = 1_791_648_000_000L
private const val MINUTE = 60_000L
private const val SELF = "dev_3xlhcrwNLeOI88IkAOPtCQ"
private const val MAC = "dev_yEtJsCLemxZR0R2zF9V93Q"
private const val LAPTOP = "dev_Pq0kZr1mT8sVb2nWc4xYdA"

private val populated = DevicesUiState(
    loading = false,
    devices = listOf(
        DeviceSummary(SELF, "Michał's Pixel", DeviceKind.PHONE, "android", NOW - 2 * MINUTE, hasPush = true, isSelf = true, trusted = true),
        DeviceSummary(MAC, "Work Mac", DeviceKind.DESKTOP, "macos", NOW - 40 * MINUTE, hasPush = false, isSelf = false, trusted = true),
        DeviceSummary(LAPTOP, "Home laptop", DeviceKind.DESKTOP, "macos", NOW - 3 * 24 * 60 * MINUTE, hasPush = false, isSelf = false, trusted = true),
    ),
    matrix = ReceiveMatrix(
        types = listOf(MessageTypes.NOTIFICATION_DISMISS, MessageTypes.CLIPBOARD_SET),
        senders = listOf(ReceiveSender(MAC, "Work Mac"), ReceiveSender(LAPTOP, "Home laptop")),
        allowed = mapOf(
            MessageTypes.NOTIFICATION_DISMISS to setOf(MAC, LAPTOP),
            MessageTypes.CLIPBOARD_SET to setOf(MAC),
        ),
    ),
)

@Composable
private fun Devices(state: DevicesUiState) {
    CaromTheme {
        DevicesContent(state = state, actions = DevicesActions(), now = NOW)
    }
}

@PreviewTest
@FormFactorPreviews
@Composable
fun DevicesPopulatedScreenshotTest() = Devices(populated)

@PreviewTest
@LargeFontPreview
@Composable
fun DevicesLargeFontScreenshotTest() = Devices(populated.copy(message = DevicesMessage.REMOVED_REKEY_PENDING))

@PreviewTest
@Preview(name = "Phone", showBackground = true, widthDp = 412, heightDp = 892, apiLevel = 36)
@Composable
fun DevicesOnlyThisDeviceScreenshotTest() = Devices(
    DevicesUiState(
        loading = false,
        devices = populated.devices.take(1),
        matrix = ReceiveMatrix(listOf(MessageTypes.CLIPBOARD_SET), emptyList(), emptyMap()),
    ),
)
