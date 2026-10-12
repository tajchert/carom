package com.mtajchert.carom.app.feature.clipboard.impl

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPasteGo
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import com.mtajchert.carom.app.core.designsystem.component.CaromGroup
import com.mtajchert.carom.app.core.designsystem.component.CaromSectionLabel
import com.mtajchert.carom.app.core.ui.CaromSimpleScreen
import com.mtajchert.carom.app.feature.clipboard.api.ClipboardSend

@Composable
fun ClipboardSettingsScreen(onBack: () -> Unit, viewModel: ClipboardSettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    ClipboardSettingsContent(
        state = state,
        onBack = onBack,
        onSendNow = { context.startActivity(ClipboardSend.intent(context).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)) },
        onSendTo = viewModel::setSendTo,
        onReceiveFromAll = viewModel::setReceiveFromAll,
        onReceiveFrom = viewModel::setReceiveFrom,
        onArrivalNotification = viewModel::setArrivalNotification,
        onOngoingNotification = viewModel::setOngoingNotification,
    )
}

@Composable
fun ClipboardSettingsContent(
    state: ClipboardSettingsState,
    onBack: () -> Unit,
    onSendNow: () -> Unit,
    onSendTo: (String, Boolean) -> Unit,
    onReceiveFromAll: (Boolean) -> Unit,
    onReceiveFrom: (String, Boolean) -> Unit,
    onArrivalNotification: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onOngoingNotification: (Boolean) -> Unit = {},
) {
    CaromSimpleScreen(title = stringResource(R.string.feature_clipboard_title), onBack = onBack, modifier = modifier) {
        if (!state.loaded) return@CaromSimpleScreen
        Button(
            onClick = onSendNow,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Icon(Icons.Outlined.ContentPasteGo, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.feature_clipboard_send_now), modifier = Modifier.padding(start = 8.dp))
        }

        Section(R.string.feature_clipboard_send_to) {
            if (state.sendTo.isEmpty()) {
                Text(
                    stringResource(R.string.feature_clipboard_no_devices),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            state.sendTo.forEach { CheckRow(it.name, it.checked) { checked -> onSendTo(it.id, checked) } }
        }

        Section(R.string.feature_clipboard_receive_from) {
            Text(
                stringResource(R.string.feature_clipboard_receive_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SwitchRow(stringResource(R.string.feature_clipboard_receive_any), state.receiveFromAll, onReceiveFromAll)
            if (!state.receiveFromAll) {
                state.receiveFrom.forEach { CheckRow(it.name, it.checked) { checked -> onReceiveFrom(it.id, checked) } }
            }
            if (state.receiveUpdateFailed) {
                Text(
                    stringResource(R.string.feature_clipboard_update_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        CaromGroup {
            Column(Modifier.padding(vertical = 4.dp)) {
                SwitchRow(stringResource(R.string.feature_clipboard_notification), state.arrivalNotification, onArrivalNotification)
                SwitchRow(stringResource(R.string.feature_clipboard_ongoing), state.ongoingNotification, onOngoingNotification)
            }
        }
    }
}

@Composable
private fun Section(title: Int, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CaromSectionLabel(stringResource(title))
        CaromGroup { Column(Modifier.padding(vertical = 4.dp)) { content() } }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
