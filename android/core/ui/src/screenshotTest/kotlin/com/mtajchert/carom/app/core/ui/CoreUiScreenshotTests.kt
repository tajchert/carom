package com.mtajchert.carom.app.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.mtajchert.carom.app.core.designsystem.preview.ComponentThemePreviews
import com.mtajchert.carom.app.core.designsystem.theme.CaromTheme
import com.mtajchert.carom.app.core.model.ConnectionStatus

@PreviewTest
@ComponentThemePreviews
@Composable
fun ConnectionStatusesScreenshotTest() {
    CaromTheme {
        Surface {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ConnectionStatus.entries.forEach { ConnectionStatusIndicator(it) }
                DeviceFingerprint("3xlhcrwN")
            }
        }
    }
}
