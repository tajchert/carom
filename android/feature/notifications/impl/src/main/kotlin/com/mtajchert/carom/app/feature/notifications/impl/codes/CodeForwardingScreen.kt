package com.mtajchert.carom.app.feature.notifications.impl.codes

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mtajchert.carom.app.core.designsystem.component.CaromButton
import com.mtajchert.carom.app.core.designsystem.component.CaromTextButton
import com.mtajchert.carom.app.core.designsystem.component.CaromTopAppBar
import com.mtajchert.carom.app.feature.notifications.impl.R

/** Stateless content for every [CodeForwardingUi]; the activity and the tests render this. */
@Composable
fun CodeForwardingContent(
    ui: CodeForwardingUi,
    onEnable: () -> Unit,
    onCopyCommand: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            CaromTopAppBar(title = stringResource(R.string.feature_notifications_codes_title), onNavigationClick = onClose)
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (ui) {
                CodeForwardingUi.Intro -> {
                    Body(R.string.feature_notifications_codes_intro)
                    CaromButton(onClick = onEnable, text = stringResource(R.string.feature_notifications_codes_enable), modifier = Modifier.fillMaxWidth())
                }
                CodeForwardingUi.Working, is CodeForwardingUi.Consent -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.feature_notifications_codes_working), style = MaterialTheme.typography.bodyLarge)
                }
                CodeForwardingUi.Enabled -> {
                    Body(R.string.feature_notifications_codes_enabled)
                    DoneButton(onClose)
                }
                is CodeForwardingUi.UseAdb -> {
                    Text(
                        stringResource(R.string.feature_notifications_codes_adb_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    Body(R.string.feature_notifications_codes_adb_body)
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            ui.command,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                    CaromButton(
                        onClick = { onCopyCommand(ui.command) },
                        text = stringResource(R.string.feature_notifications_codes_adb_copy),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Body(R.string.feature_notifications_codes_adb_repeat, muted = true)
                    DoneButton(onClose)
                }
                CodeForwardingUi.Unsupported -> {
                    Body(R.string.feature_notifications_codes_unsupported)
                    DoneButton(onClose)
                }
            }
        }
    }
}

@Composable
private fun Body(@StringRes id: Int, muted: Boolean = false) {
    Text(
        stringResource(id),
        style = MaterialTheme.typography.bodyLarge,
        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun DoneButton(onClose: () -> Unit) {
    CaromTextButton(onClick = onClose, text = stringResource(R.string.feature_notifications_codes_done))
}
