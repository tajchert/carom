package com.mtajchert.carom.app.feature.settings.impl

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtajchert.carom.app.core.designsystem.component.CaromCard
import com.mtajchert.carom.app.core.domain.RenameThisDeviceUseCase
import com.mtajchert.carom.app.core.model.ActionsFromMac
import com.mtajchert.carom.app.core.ui.CaromSimpleScreen

/**
 * What Settings can ask for. [onOpenNotificationApps] and [onOpenClipboardSettings] open screens
 * owned by other features; their rows are hidden while the callback is null.
 */
class SettingsActions(
    val onBack: () -> Unit = {},
    val onOpenNotificationApps: (() -> Unit)? = null,
    val onOpenClipboardSettings: (() -> Unit)? = null,
    val onActionsFromMacChanged: (ActionsFromMac) -> Unit = {},
    val onActionWindowChanged: (Int) -> Unit = {},
    val onRename: (String) -> Unit = {},
    val onPauseChanged: (Boolean) -> Unit = {},
    val onLeaveHub: () -> Unit = {},
    val onMessageShown: () -> Unit = {},
)

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenNotificationApps: (() -> Unit)? = null,
    onOpenClipboardSettings: (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.left) { if (state.left) onBack() }
    SettingsContent(
        state = state,
        actions = SettingsActions(
            onBack = onBack,
            onOpenNotificationApps = onOpenNotificationApps,
            onOpenClipboardSettings = onOpenClipboardSettings,
            onActionsFromMacChanged = viewModel::chooseActionsFromMac,
            onActionWindowChanged = viewModel::chooseActionWindow,
            onRename = viewModel::rename,
            onPauseChanged = viewModel::setForwardingPaused,
            onLeaveHub = viewModel::leaveHub,
            onMessageShown = viewModel::messageShown,
        ),
    )
}

@Composable
fun SettingsContent(state: SettingsUiState, actions: SettingsActions, modifier: Modifier = Modifier) {
    var renaming by rememberSaveable { mutableStateOf(false) }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    var showLicenses by rememberSaveable { mutableStateOf(false) }

    CaromSimpleScreen(title = stringResource(R.string.feature_settings_title), onBack = actions.onBack, modifier = modifier) {
        if (state.loading) return@CaromSimpleScreen
        state.message?.let { message ->
            CaromCard(modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(message.text()), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = actions.onMessageShown) { Text(stringResource(R.string.feature_settings_ok)) }
                }
            }
        }

        Section(stringResource(R.string.feature_settings_section_device)) {
            NavigationRow(
                title = stringResource(R.string.feature_settings_device_name),
                value = state.deviceName,
                onClick = if (state.paired && !state.busy) ({ renaming = true }) else null,
            )
        }

        Section(stringResource(R.string.feature_settings_section_forwarding)) {
            actions.onOpenNotificationApps?.let {
                NavigationRow(stringResource(R.string.feature_settings_apps), stringResource(R.string.feature_settings_apps_summary), it)
                HorizontalDivider()
            }
            actions.onOpenClipboardSettings?.let {
                NavigationRow(stringResource(R.string.feature_settings_clipboard), stringResource(R.string.feature_settings_clipboard_summary), it)
                HorizontalDivider()
            }
            ToggleRow(stringResource(R.string.feature_settings_pause), stringResource(R.string.feature_settings_pause_summary), state.forwardingPaused, actions.onPauseChanged)
        }

        Section(stringResource(R.string.feature_settings_section_actions)) {
            // One group, so TalkBack announces the three modes as a single radio group.
            Column(Modifier.selectableGroup()) {
                ActionsMode(
                    mode = ActionsFromMac.OFF,
                    title = R.string.feature_settings_actions_off,
                    summary = R.string.feature_settings_actions_off_summary,
                    selected = state.actionsFromMac,
                    onSelect = actions.onActionsFromMacChanged,
                )
                HorizontalDivider()
                ActionsMode(
                    mode = ActionsFromMac.AFTER_NOTIFICATION,
                    title = R.string.feature_settings_actions_after,
                    summary = R.string.feature_settings_actions_after_summary,
                    selected = state.actionsFromMac,
                    onSelect = actions.onActionsFromMacChanged,
                )
                if (state.actionsFromMac == ActionsFromMac.AFTER_NOTIFICATION) {
                    WindowChoices(selected = state.actionWindowMinutes, onSelect = actions.onActionWindowChanged)
                }
                HorizontalDivider()
                ActionsMode(
                    mode = ActionsFromMac.ALWAYS,
                    title = R.string.feature_settings_actions_always,
                    summary = R.string.feature_settings_actions_always_summary,
                    selected = state.actionsFromMac,
                    onSelect = actions.onActionsFromMacChanged,
                )
            }
        }

        Section(stringResource(R.string.feature_settings_section_about)) {
            state.hubUrl?.let { InfoRow(stringResource(R.string.feature_settings_hub), it) }
            state.deviceId?.let { InfoRow(stringResource(R.string.feature_settings_device_id), it, monospace = true) }
            InfoRow(stringResource(R.string.feature_settings_version), state.appVersion)
            NavigationRow(stringResource(R.string.feature_settings_licenses), null, onClick = { showLicenses = true })
        }

        if (state.paired) {
            OutlinedButton(
                onClick = { confirmLeave = true },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.feature_settings_leave), color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (renaming) {
        RenameDialog(
            current = state.deviceName,
            onConfirm = {
                renaming = false
                actions.onRename(it)
            },
            onDismiss = { renaming = false },
        )
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.feature_settings_leave_title)) },
            text = { Text(stringResource(R.string.feature_settings_leave_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    actions.onLeaveHub()
                }) { Text(stringResource(R.string.feature_settings_leave_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.feature_settings_cancel)) } },
        )
    }
    if (showLicenses) {
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            title = { Text(stringResource(R.string.feature_settings_licenses)) },
            text = {
                Text(
                    text = stringResource(R.string.feature_settings_licenses_body),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { showLicenses = false }) { Text(stringResource(R.string.feature_settings_ok)) } },
        )
    }
}

private fun SettingsMessage.text(): Int = when (this) {
    SettingsMessage.RENAMED_OFFLINE -> R.string.feature_settings_renamed_offline
    SettingsMessage.NAME_EMPTY -> R.string.feature_settings_name_empty
    SettingsMessage.NAME_TOO_LONG -> R.string.feature_settings_name_too_long
    SettingsMessage.LEAVE_FAILED -> R.string.feature_settings_leave_failed
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
        )
        CaromCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp)) { content() }
        }
    }
}

@Composable
private fun NavigationRow(title: String, value: String?, onClick: (() -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (value != null) {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InfoRow(title: String, value: String, monospace: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        SelectionContainer {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium.let { if (monospace) it.copy(fontFamily = FontFamily.Monospace) else it },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToggleRow(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ActionsMode(
    mode: ActionsFromMac,
    title: Int,
    summary: Int,
    selected: ActionsFromMac,
    onSelect: (ActionsFromMac) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = mode == selected, role = Role.RadioButton, onClick = { onSelect(mode) })
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RadioButton(selected = mode == selected, onClick = null)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(summary), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WindowChoices(selected: Int, onSelect: (Int) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.feature_settings_actions_window), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionsFromMac.WINDOW_MINUTES.forEach { minutes ->
                FilterChip(
                    selected = minutes == selected,
                    onClick = { onSelect(minutes) },
                    label = { Text(stringResource(R.string.feature_settings_actions_minutes, minutes)) },
                )
            }
        }
    }
}

@Composable
private fun RenameDialog(current: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.feature_settings_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= RenameThisDeviceUseCase.MAX_NAME_LENGTH) name = it },
                label = { Text(stringResource(R.string.feature_settings_device_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.feature_settings_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.feature_settings_cancel)) } },
    )
}
