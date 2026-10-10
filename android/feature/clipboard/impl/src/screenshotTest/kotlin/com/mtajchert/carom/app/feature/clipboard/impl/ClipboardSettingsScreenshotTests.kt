package com.mtajchert.carom.app.feature.clipboard.impl

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.mtajchert.carom.app.core.designsystem.preview.FormFactorPreviews
import com.mtajchert.carom.app.core.designsystem.preview.LargeFontPreview
import com.mtajchert.carom.app.core.designsystem.theme.CaromTheme

private val devices = listOf(
    ClipboardDeviceRow("dev_mac", "Work Mac", true),
    ClipboardDeviceRow("dev_pc", "Home laptop", false),
)

private val populated = ClipboardSettingsState(sendTo = devices, receiveFromAll = true, receiveFrom = devices, loaded = true)

@Composable
private fun Settings(state: ClipboardSettingsState) {
    CaromTheme {
        ClipboardSettingsContent(
            state = state,
            onBack = {},
            onSendNow = {},
            onSendTo = { _, _ -> },
            onReceiveFromAll = {},
            onReceiveFrom = { _, _ -> },
            onArrivalNotification = {},
        )
    }
}

@PreviewTest
@FormFactorPreviews
@Composable
fun ClipboardSettingsAnyDeviceScreenshotTest() = Settings(populated)

@PreviewTest
@Preview(name = "Phone", showBackground = true, widthDp = 412, heightDp = 892, apiLevel = 36)
@Composable
fun ClipboardSettingsSelectedDevicesScreenshotTest() = Settings(populated.copy(receiveFromAll = false, arrivalNotification = false))

@PreviewTest
@Preview(name = "Phone", showBackground = true, widthDp = 412, heightDp = 892, apiLevel = 36)
@Composable
fun ClipboardSettingsNoDevicesScreenshotTest() = Settings(ClipboardSettingsState(loaded = true))

@PreviewTest
@LargeFontPreview
@Composable
fun ClipboardSettingsLargeFontScreenshotTest() = Settings(populated.copy(receiveFromAll = false))
