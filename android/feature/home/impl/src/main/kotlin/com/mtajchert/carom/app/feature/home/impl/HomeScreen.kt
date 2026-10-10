package com.mtajchert.carom.app.feature.home.impl

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtajchert.carom.app.core.designsystem.component.CaromButton
import com.mtajchert.carom.app.core.designsystem.component.CaromCard
import com.mtajchert.carom.app.core.designsystem.component.CaromLoadingIndicator
import com.mtajchert.carom.app.core.designsystem.component.CaromOutlinedButton
import com.mtajchert.carom.app.core.designsystem.component.CaromTopAppBar
import com.mtajchert.carom.app.core.model.RecentItem
import com.mtajchert.carom.app.core.model.RecentItemKind
import com.mtajchert.carom.app.core.ui.ConnectionStatusIndicator
import com.mtajchert.carom.app.feature.clipboard.api.ClipboardSend

/** Everything Home can ask the app to do. Navigation lambdas come from the nav host. */
class HomeActions(
    val onSetUp: () -> Unit = {},
    val onAddDevice: () -> Unit = {},
    val onOpenDevices: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onSendClipboard: () -> Unit = {},
    val onOpenNotificationAccess: () -> Unit = {},
    val onPairAgain: () -> Unit = {},
    val onDismissNotices: () -> Unit = {},
)

@Composable
fun HomeScreen(
    onSetUp: () -> Unit,
    onAddDevice: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    HomeContent(
        state = state,
        actions = HomeActions(
            onSetUp = onSetUp,
            onAddDevice = onAddDevice,
            onOpenDevices = onOpenDevices,
            onOpenSettings = onOpenSettings,
            onSendClipboard = { context.openClipboardSender() },
            onOpenNotificationAccess = { context.openNotificationAccessSettings() },
            onPairAgain = viewModel::pairAgain,
            onDismissNotices = viewModel::dismissNotices,
        ),
    )
}

/** The clipboard feature's transparent activity reads the clipboard once it has focus, sends it and finishes. */
private fun Context.openClipboardSender() {
    startActivity(ClipboardSend.intent(this))
}

private fun Context.openNotificationAccessSettings() {
    try {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    } catch (_: ActivityNotFoundException) {
        startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

@Composable
fun HomeContent(
    state: HomeUiState,
    actions: HomeActions,
    modifier: Modifier = Modifier,
    now: Long = System.currentTimeMillis(),
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            CaromTopAppBar(
                title = stringResource(R.string.feature_home_title),
                actions = {
                    if (state is HomeUiState.Paired) {
                        IconButton(onClick = actions.onOpenDevices) {
                            Icon(Icons.Outlined.Devices, contentDescription = stringResource(R.string.feature_home_devices))
                        }
                    }
                    IconButton(onClick = actions.onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.feature_home_settings))
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            when (state) {
                HomeUiState.Loading -> CaromLoadingIndicator(Modifier.fillMaxSize())
                HomeUiState.NotPaired -> NotPaired(actions.onSetUp)
                is HomeUiState.Paired -> Paired(state, actions, now)
            }
        }
    }
}

@Composable
private fun NotPaired(onSetUp: () -> Unit) {
    Column(
        modifier = Modifier
            .widthIn(max = 600.dp)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.feature_home_not_paired_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.feature_home_not_paired_body), style = MaterialTheme.typography.bodyLarge)
        CaromButton(onClick = onSetUp, text = stringResource(R.string.feature_home_set_up), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Paired(state: HomeUiState.Paired, actions: HomeActions, now: Long) {
    var confirmPairAgain by rememberSaveable { mutableStateOf(false) }
    val mustPairAgain = HomeBanner.PairAgain in state.banners
    Column(
        modifier = Modifier
            .widthIn(max = 600.dp)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        state.banners.filterNot { it is HomeBanner.DeviceRemoved }.forEach { banner ->
            when (banner) {
                HomeBanner.PairAgain -> Banner(
                    icon = Icons.Outlined.ErrorOutline,
                    text = stringResource(R.string.feature_home_banner_pair_again),
                    action = stringResource(R.string.feature_home_banner_pair_again_action),
                    onAction = { confirmPairAgain = true },
                    error = true,
                )
                HomeBanner.NotificationAccess -> Banner(
                    icon = Icons.Outlined.Info,
                    text = stringResource(R.string.feature_home_banner_notification_access),
                    action = stringResource(R.string.feature_home_banner_notification_access_action),
                    onAction = actions.onOpenNotificationAccess,
                )
                is HomeBanner.DeviceRemoved -> Unit
            }
        }
        val removals = state.banners.filterIsInstance<HomeBanner.DeviceRemoved>()
        if (removals.isNotEmpty()) {
            Banner(
                icon = Icons.Outlined.Info,
                text = removals.map { removalText(it.item) }.joinToString("\n"),
                action = stringResource(R.string.feature_home_banner_dismiss),
                onAction = actions.onDismissNotices,
            )
        }
        StatusCard(state, now)
        if (!mustPairAgain) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                CaromButton(onClick = actions.onSendClipboard, text = stringResource(R.string.feature_home_send_clipboard))
                CaromOutlinedButton(onClick = actions.onAddDevice, text = stringResource(R.string.feature_home_add_device))
            }
        }
        if (state.recent.isNotEmpty()) {
            Text(
                text = stringResource(R.string.feature_home_recent),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            state.recent.forEach { RecentRow(it, now) }
        }
    }
    if (confirmPairAgain) {
        AlertDialog(
            onDismissRequest = { confirmPairAgain = false },
            title = { Text(stringResource(R.string.feature_home_pair_again_title)) },
            text = { Text(stringResource(R.string.feature_home_pair_again_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmPairAgain = false
                    actions.onPairAgain()
                }) { Text(stringResource(R.string.feature_home_pair_again_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmPairAgain = false }) { Text(stringResource(R.string.feature_home_cancel)) }
            },
        )
    }
}

@Composable
private fun StatusCard(state: HomeUiState.Paired, now: Long) {
    CaromCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = state.deviceName ?: stringResource(R.string.feature_home_this_device),
                style = MaterialTheme.typography.titleMedium,
            )
            ConnectionStatusIndicator(state.connection)
            Detail(stringResource(R.string.feature_home_hub, state.hubHost))
            Detail(
                state.lastSyncAt?.let { stringResource(R.string.feature_home_last_sync, relativeTime(it, now)) }
                    ?: stringResource(R.string.feature_home_last_sync_never),
            )
            if (state.pendingMessages > 0) {
                Text(
                    text = pluralStringResource(R.plurals.feature_home_pending, state.pendingMessages, state.pendingMessages),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun Detail(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Banner(
    icon: ImageVector,
    text: String,
    action: String? = null,
    onAction: () -> Unit = {},
    error: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val container: Color = if (error) colors.errorContainer else colors.secondaryContainer
    val content: Color = if (error) colors.onErrorContainer else colors.onSecondaryContainer
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                if (action != null) {
                    TextButton(onClick = onAction, modifier = Modifier.align(Alignment.End)) {
                        Text(action, color = content)
                    }
                }
            }
        }
    }
}

@Composable
private fun removalText(item: RecentItem): String = when {
    item.kind == RecentItemKind.DEVICE_REMOVED_NO_REKEY -> stringResource(R.string.feature_home_removed_no_rekey, item.summary)
    item.actor != null -> stringResource(R.string.feature_home_removed_by, item.actor!!, item.summary)
    else -> stringResource(R.string.feature_home_recent_device_removed, item.summary)
}

@Composable
private fun RecentRow(item: RecentItem, now: Long) {
    val text = when (item.kind) {
        RecentItemKind.CLIPBOARD_SENT -> stringResource(R.string.feature_home_recent_clipboard_sent, item.summary)
        RecentItemKind.CLIPBOARD_RECEIVED -> stringResource(R.string.feature_home_recent_clipboard_received, item.summary)
        RecentItemKind.DEVICE_REMOVED, RecentItemKind.DEVICE_REMOVED_NO_REKEY -> removalText(item)
    }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            imageVector = if (item.kind.isClipboard()) Icons.Outlined.ContentPaste else Icons.Outlined.Devices,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f)) {
            Text(text = text, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            Text(
                text = relativeTime(item.at, now),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun RecentItemKind.isClipboard() = this == RecentItemKind.CLIPBOARD_SENT || this == RecentItemKind.CLIPBOARD_RECEIVED

/** "just now", "5 minutes ago", "3 hours ago", "2 days ago". */
@Composable
internal fun relativeTime(at: Long, now: Long): String {
    val minutes = ((now - at).coerceAtLeast(0) / 60_000).toInt()
    return when {
        minutes < 1 -> stringResource(R.string.feature_home_just_now)
        minutes < 60 -> pluralStringResource(R.plurals.feature_home_minutes_ago, minutes, minutes)
        minutes < 24 * 60 -> pluralStringResource(R.plurals.feature_home_hours_ago, minutes / 60, minutes / 60)
        else -> pluralStringResource(R.plurals.feature_home_days_ago, minutes / (24 * 60), minutes / (24 * 60))
    }
}
