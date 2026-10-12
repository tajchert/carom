package com.mtajchert.carom.app.feature.home.impl

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContentPasteGo
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.LaptopMac
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.SouthWest
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.mtajchert.carom.app.core.designsystem.component.CaromMark
import com.mtajchert.carom.app.core.model.ConnectionStatus
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import com.mtajchert.carom.app.core.designsystem.component.CaromLoadingIndicator
import com.mtajchert.carom.app.core.model.RecentItem
import com.mtajchert.carom.app.core.model.RecentItemKind
import com.mtajchert.carom.app.feature.clipboard.api.ClipboardSend

/** Everything Home can ask the app to do. Navigation lambdas come from the nav host. */
class HomeActions(
    val onSetUp: () -> Unit = {},
    val onAddDevice: () -> Unit = {},
    val onOpenDevices: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onOpenApps: () -> Unit = {},
    val onSendClipboard: () -> Unit = {},
    val onOpenNotificationAccess: () -> Unit = {},
    val onOpenDateSettings: () -> Unit = {},
    val onPairAgain: () -> Unit = {},
    val onDismissNotices: () -> Unit = {},
)

@Composable
fun HomeScreen(
    onSetUp: () -> Unit,
    onAddDevice: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenApps: () -> Unit,
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
            onOpenApps = onOpenApps,
            onSendClipboard = { context.openClipboardSender() },
            onOpenNotificationAccess = { context.openNotificationAccessSettings() },
            onOpenDateSettings = { context.openDateSettings() },
            onPairAgain = viewModel::pairAgain,
            onDismissNotices = viewModel::dismissNotices,
        ),
    )
}

/** The clipboard feature's transparent activity reads the clipboard once it has focus, sends it and finishes. */
private fun Context.openClipboardSender() {
    startActivity(ClipboardSend.intent(this))
}

private fun Context.openDateSettings() {
    try {
        startActivity(Intent(Settings.ACTION_DATE_SETTINGS))
    } catch (_: ActivityNotFoundException) {
        startActivity(Intent(Settings.ACTION_SETTINGS))
    }
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
            HomeTopBar(
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
                is HomeBanner.ClockOff -> Banner(
                    icon = Icons.Outlined.ErrorOutline,
                    text = pluralStringResource(
                        if (banner.ahead) R.plurals.feature_home_banner_clock_ahead else R.plurals.feature_home_banner_clock_behind,
                        banner.minutes.toInt(),
                        banner.minutes,
                    ),
                    action = stringResource(R.string.feature_home_banner_clock_action),
                    onAction = actions.onOpenDateSettings,
                    error = true,
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
            SendClipboardButton(actions.onSendClipboard)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                ActionTile(
                    icon = Icons.Outlined.Apps,
                    title = stringResource(R.string.feature_home_apps),
                    onClick = actions.onOpenApps,
                    modifier = Modifier.weight(1f),
                )
                ActionTile(
                    icon = Icons.Outlined.QrCode2,
                    title = stringResource(R.string.feature_home_add_device),
                    onClick = actions.onAddDevice,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (state.recent.isNotEmpty()) {
            Text(
                text = stringResource(R.string.feature_home_recent),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp).semantics { heading() },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBar(actions: @Composable () -> Unit) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CaromMark(size = 22.dp)
                Text(
                    text = stringResource(R.string.feature_home_title),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium),
                )
            }
        },
        actions = { actions() },
    )
}

private enum class PathMode { Live, Offline, Rejected }

private fun ConnectionStatus.pathMode() = when (this) {
    ConnectionStatus.OFFLINE -> PathMode.Offline
    ConnectionStatus.REJECTED -> PathMode.Rejected
    else -> PathMode.Live
}

@Composable
private fun StatusCard(state: HomeUiState.Paired, now: Long) {
    val colors = MaterialTheme.colorScheme
    val mode = state.connection.pathMode()
    val container = when (mode) {
        PathMode.Live -> colors.primaryContainer
        PathMode.Offline -> colors.surfaceContainer
        PathMode.Rejected -> colors.errorContainer
    }
    val content = when (mode) {
        PathMode.Live -> colors.onPrimaryContainer
        PathMode.Offline -> colors.onSurface
        PathMode.Rejected -> colors.onErrorContainer
    }
    val phone = state.deviceName ?: stringResource(R.string.feature_home_this_device)
    val (title, subtitle) = when {
        state.connection == ConnectionStatus.SYNCING -> stringResource(R.string.feature_home_status_syncing) to phone
        mode == PathMode.Live -> stringResource(R.string.feature_home_status_forwarding) to phone
        mode == PathMode.Offline ->
            stringResource(R.string.feature_home_status_waiting) to stringResource(R.string.feature_home_status_retrying)
        else -> stringResource(R.string.feature_home_status_refused) to stringResource(R.string.feature_home_status_refused_detail)
    }
    val (pillIcon, pillLabel) = when (state.connection) {
        ConnectionStatus.OFFLINE -> Icons.Outlined.CloudOff to R.string.feature_home_pill_offline
        ConnectionStatus.REJECTED -> Icons.Outlined.ErrorOutline to R.string.feature_home_pill_refused
        ConnectionStatus.SYNCING -> Icons.Filled.Sync to R.string.feature_home_pill_syncing
        else -> Icons.Filled.CheckCircle to R.string.feature_home_pill_live
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        border = if (mode == PathMode.Offline) BorderStroke(1.dp, colors.outlineVariant) else null,
    ) {
        Column(
            Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(text = title, style = MaterialTheme.typography.headlineMedium)
                    Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = content.copy(alpha = 0.8f))
                }
                Spacer(Modifier.width(12.dp))
                StatusPill(pillIcon, stringResource(pillLabel), content, mode)
            }
            PathRow(state.hubHost, mode, content)
            HorizontalDivider(color = content.copy(alpha = 0.15f))
            StatusFooter(state, mode, now)
        }
    }
}

@Composable
private fun StatusPill(icon: ImageVector, label: String, content: Color, mode: PathMode) {
    val bg = if (mode == PathMode.Live) Color.White.copy(alpha = 0.55f) else content.copy(alpha = 0.08f)
    Row(
        modifier = Modifier.background(bg, CircleShape).padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/** phone - hub - other devices, drawn as one line; dashed and greyed while the Hub is out of reach. */
@Composable
private fun PathRow(hubHost: String, mode: PathMode, content: Color) {
    val colors = MaterialTheme.colorScheme
    val live = mode == PathMode.Live
    val lineColor = if (live) colors.primary else colors.outline
    val label = content.copy(alpha = 0.8f)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PathNode(Icons.Outlined.Smartphone, if (live) colors.primary else colors.outline, colors.surface)
            PathLine(lineColor, dashed = !live, modifier = Modifier.weight(1f))
            Box(Modifier.size(30.dp).border(2.dp, lineColor, CircleShape))
            PathLine(lineColor, dashed = !live, modifier = Modifier.weight(1f))
            PathNode(
                Icons.Outlined.LaptopMac,
                if (live) colors.primary else colors.surfaceVariant,
                if (live) colors.onPrimary else colors.outline,
            )
        }
        Row(Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.feature_home_this_device),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
                color = label,
            )
            Text(
                hubHost,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(2f),
                color = label,
            )
            Text(
                stringResource(R.string.feature_home_other_devices),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
                color = label,
            )
        }
    }
}

@Composable
private fun PathNode(icon: ImageVector, background: Color, tint: Color) {
    Box(Modifier.size(40.dp).background(background, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun PathLine(color: Color, dashed: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.height(2.dp)) {
        drawLine(
            color = color,
            start = Offset(0f, size.height / 2),
            end = Offset(size.width, size.height / 2),
            strokeWidth = 2.dp.toPx(),
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())) else null,
        )
    }
}

@Composable
private fun StatusFooter(state: HomeUiState.Paired, mode: PathMode, now: Long) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.pendingMessages > 0) {
            Box(Modifier.size(24.dp).background(colors.primary, CircleShape), contentAlignment = Alignment.Center) {
                Text(state.pendingMessages.toString(), style = MaterialTheme.typography.labelMedium, color = colors.onPrimary)
            }
            Text(
                pluralStringResource(R.plurals.feature_home_pending_short, state.pendingMessages),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        } else {
            Icon(Icons.Outlined.DoneAll, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = state.lastSyncAt?.let { stringResource(R.string.feature_home_last_sync, relativeTime(it, now)) }
                    ?: stringResource(R.string.feature_home_last_sync_never),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
        if (mode == PathMode.Live) {
            Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(stringResource(R.string.feature_home_encrypted), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SendClipboardButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = CircleShape,
    ) {
        Icon(Icons.Outlined.ContentPasteGo, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.feature_home_send_clipboard), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ActionTile(icon: ImageVector, title: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(92.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
    }
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
            imageVector = when (item.kind) {
                RecentItemKind.CLIPBOARD_RECEIVED -> Icons.Outlined.SouthWest
                RecentItemKind.CLIPBOARD_SENT -> Icons.Outlined.NorthEast
                else -> Icons.Outlined.Devices
            },
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.primary,
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
