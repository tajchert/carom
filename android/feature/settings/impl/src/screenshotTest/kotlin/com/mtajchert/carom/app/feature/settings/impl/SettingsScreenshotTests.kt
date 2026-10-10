package com.mtajchert.carom.app.feature.settings.impl

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.mtajchert.carom.app.core.designsystem.preview.FormFactorPreviews
import com.mtajchert.carom.app.core.designsystem.preview.LargeFontPreview
import com.mtajchert.carom.app.core.designsystem.theme.CaromTheme
import com.mtajchert.carom.app.core.model.PushState

private val paired = SettingsUiState(
    loading = false,
    paired = true,
    deviceName = "Michał's Pixel",
    forwardingPaused = false,
    push = PushState.REGISTERED,
    hubUrl = "https://carom-hub.example.workers.dev",
    deviceId = "dev_3xlhcrwNLeOI88IkAOPtCQ",
    appVersion = "1.0.0",
)

private val withEntries = SettingsActions(onOpenNotificationApps = {}, onOpenClipboardSettings = {})

@Composable
private fun Settings(state: SettingsUiState, actions: SettingsActions = withEntries) {
    CaromTheme { SettingsContent(state = state, actions = actions) }
}

@PreviewTest
@FormFactorPreviews
@Composable
fun SettingsPairedScreenshotTest() = Settings(paired)

@PreviewTest
@LargeFontPreview
@Composable
fun SettingsLargeFontScreenshotTest() = Settings(paired.copy(forwardingPaused = true, push = PushState.NO_DISTRIBUTOR))

@PreviewTest
@Preview(name = "Phone", showBackground = true, widthDp = 412, heightDp = 892, apiLevel = 36)
@Composable
fun SettingsNotPairedScreenshotTest() = Settings(
    SettingsUiState(loading = false, paired = false, deviceName = "Pixel 9", appVersion = "1.0.0"),
    actions = SettingsActions(),
)
