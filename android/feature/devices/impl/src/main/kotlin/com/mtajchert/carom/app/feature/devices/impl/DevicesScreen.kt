package com.mtajchert.carom.app.feature.devices.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.LaptopMac
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Terminal
import com.mtajchert.carom.app.core.designsystem.component.CaromCard
import com.mtajchert.carom.app.core.designsystem.component.CaromGroup
import com.mtajchert.carom.app.core.designsystem.component.CaromIconTile
import com.mtajchert.carom.app.core.designsystem.component.CaromStateChip
import com.mtajchert.carom.app.core.domain.ReceiveMatrix
import com.mtajchert.carom.app.core.domain.RenameThisDeviceUseCase
import com.mtajchert.carom.app.core.model.DeviceKind
import com.mtajchert.carom.app.core.model.DeviceSummary
import com.mtajchert.carom.app.core.protocol.MessageTypes
import com.mtajchert.carom.app.core.ui.CaromSimpleScreen

/** What the device list can ask for; the stateful screen binds these to the ViewModel. */
class DevicesActions(
    val onBack: () -> Unit = {},
    val onRename: (String) -> Unit = {},
    val onRemove: (String) -> Unit = {},
    val onSetReceive: (type: String, senderId: String, allow: Boolean) -> Unit = { _, _, _ -> },
    val onMessageShown: () -> Unit = {},
)

@Composable
fun DevicesScreen(onBack: () -> Unit, viewModel: DevicesViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.left) { if (state.left) onBack() }
    DevicesContent(
        state = state,
        actions = DevicesActions(
            onBack = onBack,
            onRename = viewModel::rename,
            onRemove = viewModel::remove,
            onSetReceive = viewModel::setReceive,
            onMessageShown = viewModel::messageShown,
        ),
    )
}

@Composable
fun DevicesContent(
    state: DevicesUiState,
    actions: DevicesActions,
    modifier: Modifier = Modifier,
    now: Long = System.currentTimeMillis(),
) {
    var renaming by rememberSaveable { mutableStateOf(false) }
    var removingId by rememberSaveable { mutableStateOf<String?>(null) }

    CaromSimpleScreen(title = stringResource(R.string.feature_devices_title), onBack = actions.onBack, modifier = modifier) {
        state.message?.let { message ->
            MessageCard(stringResource(message.text()), onDismiss = actions.onMessageShown)
        }
        if (!state.loading && state.devices.isEmpty()) {
            Text(stringResource(R.string.feature_devices_empty), style = MaterialTheme.typography.bodyLarge)
        }
        state.devices.forEach { device ->
            DeviceRow(
                device = device,
                now = now,
                enabled = !state.busy,
                onRename = { renaming = true },
                onRemove = { removingId = device.id },
            )
        }
        if (state.devices.any { it.isSelf }) {
            ReceiveMatrixSection(state.matrix, enabled = !state.busy, onSetReceive = actions.onSetReceive)
        }
    }

    val self = state.devices.firstOrNull { it.isSelf }
    if (renaming && self != null) {
        RenameDialog(
            current = self.name.orEmpty(),
            onConfirm = {
                renaming = false
                actions.onRename(it)
            },
            onDismiss = { renaming = false },
        )
    }
    state.devices.firstOrNull { it.id == removingId }?.let { device ->
        RemoveDialog(
            device = device,
            onConfirm = {
                removingId = null
                actions.onRemove(device.id)
            },
            onDismiss = { removingId = null },
        )
    }
}

private fun DevicesMessage.text(): Int = when (this) {
    DevicesMessage.REMOVED -> R.string.feature_devices_removed
    DevicesMessage.REMOVED_REKEY_PENDING -> R.string.feature_devices_removed_rekey_pending
    DevicesMessage.REMOVE_FAILED -> R.string.feature_devices_remove_failed
    DevicesMessage.RENAMED -> R.string.feature_devices_renamed
    DevicesMessage.RENAMED_OFFLINE -> R.string.feature_devices_renamed_offline
    DevicesMessage.NAME_EMPTY -> R.string.feature_devices_name_empty
    DevicesMessage.NAME_TOO_LONG -> R.string.feature_devices_name_too_long
    DevicesMessage.RULE_SAVED_OFFLINE -> R.string.feature_devices_rule_offline
}

@Composable
private fun MessageCard(text: String, onDismiss: () -> Unit) {
    CaromCard(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.feature_devices_ok)) }
        }
    }
}

@Composable
private fun DeviceRow(device: DeviceSummary, now: Long, enabled: Boolean, onRename: () -> Unit, onRemove: () -> Unit) {
    CaromGroup {
        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val name = device.name ?: stringResource(R.string.feature_devices_unknown, device.fingerprint)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CaromIconTile(
                    icon = when (device.kind) {
                        DeviceKind.PHONE -> Icons.Outlined.Smartphone
                        DeviceKind.DESKTOP -> Icons.Outlined.LaptopMac
                        DeviceKind.CLI -> Icons.Outlined.Terminal
                        null -> Icons.Outlined.DevicesOther
                    },
                    size = 48.dp,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = if (device.isSelf) stringResource(R.string.feature_devices_this_device, name) else name,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val kind = when (device.kind) {
                        DeviceKind.PHONE -> stringResource(R.string.feature_devices_kind_phone)
                        DeviceKind.DESKTOP -> stringResource(R.string.feature_devices_kind_desktop)
                        DeviceKind.CLI -> stringResource(R.string.feature_devices_kind_cli)
                        null -> stringResource(R.string.feature_devices_kind_unverified)
                    }
                    Secondary(stringResource(R.string.feature_devices_details, kind, device.fingerprint), monospace = false)
                    Secondary(
                        device.lastSeen?.let { stringResource(R.string.feature_devices_last_seen, relativeTime(it, now)) }
                            ?: stringResource(R.string.feature_devices_last_seen_never),
                    )
                }
                if (device.kind != null) {
                    CaromStateChip(Icons.Filled.CheckCircle, stringResource(R.string.feature_devices_chip_verified))
                } else {
                    CaromStateChip(
                        icon = Icons.Outlined.GppMaybe,
                        label = stringResource(R.string.feature_devices_chip_unverified),
                        container = MaterialTheme.colorScheme.errorContainer,
                        content = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (device.isSelf) {
                    TextButton(onClick = onRename, enabled = enabled) { Text(stringResource(R.string.feature_devices_rename)) }
                }
                TextButton(onClick = onRemove, enabled = enabled) {
                    Text(stringResource(R.string.feature_devices_remove), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun Secondary(text: String, monospace: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium.let { if (monospace) it.copy(fontFamily = FontFamily.Monospace) else it },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ReceiveMatrixSection(matrix: ReceiveMatrix, enabled: Boolean, onSetReceive: (String, String, Boolean) -> Unit) {
    Text(
        text = stringResource(R.string.feature_devices_receive_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .padding(start = 8.dp, top = 8.dp)
            .semantics { heading() },
    )
    Text(
        text = stringResource(R.string.feature_devices_receive_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp),
    )
    if (matrix.senders.isEmpty() || matrix.types.isEmpty()) {
        Text(stringResource(R.string.feature_devices_receive_empty), style = MaterialTheme.typography.bodyMedium)
        return
    }
    CaromGroup {
        Column(Modifier.padding(vertical = 8.dp)) {
            matrix.types.forEachIndexed { index, type ->
                if (index > 0) Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(typeLabel(type)),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                matrix.senders.forEach { sender ->
                    val allowed = matrix.isAllowed(type, sender.id)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = allowed,
                                enabled = enabled,
                                role = Role.Checkbox,
                                onValueChange = { onSetReceive(type, sender.id, it) },
                            )
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.feature_devices_receive_from, sender.name),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Checkbox(checked = allowed, onCheckedChange = null, enabled = enabled)
                    }
                }
            }
        }
    }
}

private fun typeLabel(type: String): Int = when (type) {
    MessageTypes.NOTIFICATION_POSTED -> R.string.feature_devices_type_notifications
    MessageTypes.NOTIFICATION_REMOVED -> R.string.feature_devices_type_notification_removals
    MessageTypes.NOTIFICATION_DISMISS -> R.string.feature_devices_type_dismiss
    MessageTypes.CLIPBOARD_SET -> R.string.feature_devices_type_clipboard
    MessageTypes.DEVICE_STATUS -> R.string.feature_devices_type_status
    else -> R.string.feature_devices_type_other
}

@Composable
private fun RenameDialog(current: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.feature_devices_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= RenameThisDeviceUseCase.MAX_NAME_LENGTH) name = it },
                label = { Text(stringResource(R.string.feature_devices_rename_label)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.feature_devices_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.feature_devices_cancel)) } },
    )
}

@Composable
private fun RemoveDialog(device: DeviceSummary, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val name = device.name ?: device.fingerprint
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (device.isSelf) stringResource(R.string.feature_devices_remove_self_title) else stringResource(R.string.feature_devices_remove_title, name),
            )
        },
        text = {
            Text(stringResource(if (device.isSelf) R.string.feature_devices_remove_self_body else R.string.feature_devices_remove_body))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.feature_devices_remove), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.feature_devices_cancel)) } },
    )
}

/** "just now", "5 minutes ago", "3 hours ago", "2 days ago". */
@Composable
private fun relativeTime(at: Long, now: Long): String {
    val minutes = ((now - at).coerceAtLeast(0) / 60_000).toInt()
    return when {
        minutes < 1 -> stringResource(R.string.feature_devices_just_now)
        minutes < 60 -> pluralStringResource(R.plurals.feature_devices_minutes_ago, minutes, minutes)
        minutes < 24 * 60 -> pluralStringResource(R.plurals.feature_devices_hours_ago, minutes / 60, minutes / 60)
        else -> pluralStringResource(R.plurals.feature_devices_days_ago, minutes / (24 * 60), minutes / (24 * 60))
    }
}
