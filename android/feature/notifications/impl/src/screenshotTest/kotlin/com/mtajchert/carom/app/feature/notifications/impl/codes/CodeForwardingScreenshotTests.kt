package com.mtajchert.carom.app.feature.notifications.impl.codes

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.mtajchert.carom.app.core.designsystem.theme.CaromTheme

@Composable
private fun Codes(ui: CodeForwardingUi) {
    CaromTheme(dynamicColor = false) {
        CodeForwardingContent(ui = ui, onEnable = {}, onCopyCommand = {}, onClose = {})
    }
}

@PreviewTest
@Preview(name = "Phone", showBackground = true, widthDp = 412, heightDp = 640, apiLevel = 36)
@Composable
fun CodesIntroScreenshotTest() = Codes(CodeForwardingUi.Intro)

@PreviewTest
@Preview(name = "Phone", showBackground = true, widthDp = 412, heightDp = 640, apiLevel = 36)
@Composable
fun CodesAdbScreenshotTest() = Codes(CodeForwardingUi.UseAdb(AdbFallback.command("com.mtajchert.carom.app")))

@PreviewTest
@Preview(name = "Phone", showBackground = true, widthDp = 412, heightDp = 640, apiLevel = 36)
@Composable
fun CodesEnabledScreenshotTest() = Codes(CodeForwardingUi.Enabled)
