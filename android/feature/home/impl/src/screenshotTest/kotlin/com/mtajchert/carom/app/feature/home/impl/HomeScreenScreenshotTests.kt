package com.mtajchert.carom.app.feature.home.impl

import androidx.compose.runtime.Composable
import com.android.tools.screenshot.PreviewTest
import com.mtajchert.carom.app.core.designsystem.preview.FormFactorPreviews
import com.mtajchert.carom.app.core.designsystem.preview.LargeFontPreview
import com.mtajchert.carom.app.core.designsystem.theme.CaromTheme
import com.mtajchert.carom.app.core.model.ConnectionStatus
import com.mtajchert.carom.app.core.model.RecentItem
import com.mtajchert.carom.app.core.model.RecentItemKind
import androidx.compose.ui.tooling.preview.Preview

private val paired = HomeUiState.Paired(
    deviceName = "Michał's Pixel",
    hubHost = "carom-hub.example.workers.dev",
    connection = ConnectionStatus.IDLE,
    pendingMessages = 2,
    recent = listOf(
        RecentItem(1, RecentItemKind.CLIPBOARD_RECEIVED, "Meeting notes for Friday", "dev_a", 0),
        RecentItem(2, RecentItemKind.DEVICE_REMOVED, "Old laptop", "dev_b", 0),
    ),
)

@Composable
private fun Home(state: HomeUiState) {
    CaromTheme {
        HomeContent(state = state, onSetUp = {}, onAddDevice = {}, onOpenDevices = {}, onOpenSettings = {})
    }
}

@PreviewTest
@Preview(name = "Phone", showBackground = true, widthDp = 412, heightDp = 892, apiLevel = 36)
@Composable
fun HomeNotPairedScreenshotTest() = Home(HomeUiState.NotPaired)

@PreviewTest
@FormFactorPreviews
@Composable
fun HomePairedScreenshotTest() = Home(paired)

@PreviewTest
@LargeFontPreview
@Composable
fun HomePairedLargeFontScreenshotTest() = Home(paired.copy(connection = ConnectionStatus.OFFLINE))
