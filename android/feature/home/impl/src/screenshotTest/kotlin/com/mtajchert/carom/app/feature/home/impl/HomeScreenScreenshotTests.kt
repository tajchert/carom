package com.mtajchert.carom.app.feature.home.impl

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.mtajchert.carom.app.core.designsystem.preview.FormFactorPreviews
import com.mtajchert.carom.app.core.designsystem.preview.LargeFontPreview
import com.mtajchert.carom.app.core.designsystem.theme.CaromTheme
import com.mtajchert.carom.app.core.model.ConnectionStatus
import com.mtajchert.carom.app.core.model.RecentItem
import com.mtajchert.carom.app.core.model.RecentItemKind

private const val NOW = 1_791_648_000_000L
private const val MINUTE = 60_000L

private val paired = HomeUiState.Paired(
    deviceName = "Michał's Pixel",
    hubHost = "carom-hub.example.workers.dev",
    connection = ConnectionStatus.IDLE,
    pendingMessages = 2,
    lastSyncAt = NOW - 3 * MINUTE,
    recent = listOf(
        RecentItem(1, RecentItemKind.CLIPBOARD_RECEIVED, "Meeting notes for Friday", "dev_a", NOW - 2 * MINUTE),
        RecentItem(2, RecentItemKind.DEVICE_REMOVED, "Old laptop", "dev_b", NOW - 90 * MINUTE, actor = "Work Mac"),
    ),
)

@Composable
private fun Home(state: HomeUiState) {
    CaromTheme(dynamicColor = false) {
        HomeContent(state = state, actions = HomeActions(), now = NOW)
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

@PreviewTest
@Preview(name = "Phone", showBackground = true, widthDp = 412, heightDp = 892, apiLevel = 36)
@Composable
fun HomeProblemsScreenshotTest() = Home(
    paired.copy(
        banners = listOf(
            HomeBanner.NotificationAccess,
            HomeBanner.DeviceRemoved(paired.recent[1]),
            HomeBanner.DeviceRemoved(RecentItem(3, RecentItemKind.DEVICE_REMOVED_NO_REKEY, "Tablet", "dev_c", NOW)),
        ),
    ),
)

@PreviewTest
@Preview(name = "Phone", showBackground = true, widthDp = 412, heightDp = 892, apiLevel = 36)
@Composable
fun HomePairAgainScreenshotTest() = Home(
    paired.copy(connection = ConnectionStatus.REJECTED, pendingMessages = 0, banners = listOf(HomeBanner.PairAgain)),
)

@PreviewTest
@Preview(name = "Phone", showBackground = true, widthDp = 412, heightDp = 892, apiLevel = 36)
@Composable
fun HomePausedScreenshotTest() = Home(paired.copy(paused = true))
