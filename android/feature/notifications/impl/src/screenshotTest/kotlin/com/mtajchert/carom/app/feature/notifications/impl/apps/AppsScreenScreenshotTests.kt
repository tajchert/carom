package com.mtajchert.carom.app.feature.notifications.impl.apps

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.mtajchert.carom.app.core.designsystem.preview.FormFactorPreviews
import com.mtajchert.carom.app.core.designsystem.preview.LargeFontPreview
import com.mtajchert.carom.app.core.designsystem.theme.CaromTheme
import com.mtajchert.carom.app.core.model.AppForwardingMode
import com.mtajchert.carom.app.core.model.NotificationAppSetting

private val populated = AppsUiState(
    loading = false,
    apps = listOf(
        NotificationAppSetting("com.google.android.calendar", "Calendar", AppForwardingMode.FORWARD, 0),
        NotificationAppSetting("com.google.android.gm", "Gmail", AppForwardingMode.FORWARD, 0, forwardOngoing = true),
        NotificationAppSetting("com.example.bank", "My Bank", AppForwardingMode.HIDE_CONTENT, 0),
        NotificationAppSetting("org.thoughtcrime.securesms", "Signal", AppForwardingMode.FORWARD, 0),
        NotificationAppSetting("com.android.systemui", "System UI", AppForwardingMode.OFF, 0),
    ),
    totalApps = 5,
    forwardCount = 3,
    hiddenCount = 1,
    offCount = 1,
)

@Composable
private fun Apps(state: AppsUiState) {
    CaromTheme(dynamicColor = false) {
        AppsContent(
            state = state,
            onBack = {},
            onQueryChange = {},
            onFilterChange = {},
            onModeChange = { _, _ -> },
            onForwardOngoingChange = { _, _ -> },
            onOpenAccessSettings = {},
        )
    }
}

@PreviewTest
@FormFactorPreviews
@Composable
fun AppsPopulatedScreenshotTest() = Apps(populated)

@PreviewTest
@LargeFontPreview
@Composable
fun AppsPopulatedLargeFontScreenshotTest() = Apps(populated)

@PreviewTest
@Preview(name = "Phone", showBackground = true, widthDp = 412, heightDp = 892, apiLevel = 36)
@Composable
fun AppsEmptyNoAccessScreenshotTest() = Apps(AppsUiState(loading = false, accessGranted = false))

@PreviewTest
@Preview(name = "Phone", showBackground = true, widthDp = 412, heightDp = 892, apiLevel = 36)
@Composable
fun AppsNoSearchResultsScreenshotTest() = Apps(
    populated.copy(query = "weather", apps = emptyList()),
)

@PreviewTest
@Preview(name = "Phone", showBackground = true, widthDp = 412, heightDp = 892, apiLevel = 36)
@Composable
fun AppsSelectionScreenshotTest() = Apps(
    populated.copy(selected = setOf("com.google.android.gm", "org.thoughtcrime.securesms")),
)
