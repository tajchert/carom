package com.mtajchert.carom.app.feature.notifications.impl.apps

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtajchert.carom.app.core.designsystem.component.CaromButton
import com.mtajchert.carom.app.core.designsystem.component.CaromCard
import com.mtajchert.carom.app.core.designsystem.component.CaromLoadingIndicator
import com.mtajchert.carom.app.core.designsystem.component.CaromTopAppBar
import com.mtajchert.carom.app.core.model.AppForwardingMode
import com.mtajchert.carom.app.core.model.NotificationAppSetting
import com.mtajchert.carom.app.feature.notifications.impl.CaromListenerService
import com.mtajchert.carom.app.feature.notifications.impl.R

@Composable
fun AppsScreen(onBack: () -> Unit, viewModel: AppsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshAccess()
        onPauseOrDispose { }
    }
    AppsContent(
        state = state,
        onBack = onBack,
        onQueryChange = viewModel::setQuery,
        onModeChange = viewModel::setMode,
        onPausedChange = viewModel::setPaused,
        onOnlyWhenLockedChange = viewModel::setOnlyWhenLocked,
        onOpenAccessSettings = { openNotificationAccessSettings(context) },
    )
}

/** Stateless "Apps" screen: global switches, search, and one Forward / Hide content / Off choice per app. */
@Composable
fun AppsContent(
    state: AppsUiState,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onModeChange: (String, AppForwardingMode) -> Unit,
    onPausedChange: (Boolean) -> Unit,
    onOnlyWhenLockedChange: (Boolean) -> Unit,
    onOpenAccessSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { CaromTopAppBar(title = stringResource(R.string.feature_notifications_apps_title), onNavigationClick = onBack) },
    ) { padding ->
        if (state.loading) {
            CaromLoadingIndicator(Modifier.padding(padding))
            return@Scaffold
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!state.accessGranted) {
                    item(key = "access") { AccessCard(onOpenAccessSettings) }
                }
                item(key = "pause") {
                    ToggleRow(stringResource(R.string.feature_notifications_pause), state.paused, onPausedChange)
                }
                item(key = "locked") {
                    ToggleRow(stringResource(R.string.feature_notifications_only_locked), state.onlyWhenLocked, onOnlyWhenLockedChange)
                }
                item(key = "divider") { HorizontalDivider() }
                if (state.totalApps == 0) {
                    item(key = "empty") { EmptyApps() }
                } else {
                    item(key = "search") { SearchField(state.query, onQueryChange) }
                    if (state.apps.isEmpty()) {
                        item(key = "no_results") {
                            Text(
                                stringResource(R.string.feature_notifications_no_results),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    }
                    items(state.apps, key = { it.packageName }) { app ->
                        AppRow(app, onModeChange = { onModeChange(app.packageName, it) })
                    }
                }
            }
        }
    }
}

@Composable
private fun AccessCard(onOpenAccessSettings: () -> Unit) {
    CaromCard {
        Text(stringResource(R.string.feature_notifications_access_missing), style = MaterialTheme.typography.bodyMedium)
        CaromButton(
            onClick = onOpenAccessSettings,
            text = stringResource(R.string.feature_notifications_access_action),
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = 16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(stringResource(R.string.feature_notifications_search)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = if (query.isEmpty()) {
            null
        } else {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.feature_notifications_clear_search))
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    )
}

@Composable
private fun EmptyApps() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.feature_notifications_empty),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.feature_notifications_empty_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val modes = listOf(
    AppForwardingMode.FORWARD to R.string.feature_notifications_mode_forward,
    AppForwardingMode.HIDE_CONTENT to R.string.feature_notifications_mode_hide,
    AppForwardingMode.OFF to R.string.feature_notifications_mode_off,
)

@Composable
private fun AppRow(app: NotificationAppSetting, onModeChange: (AppForwardingMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        Column {
            Text(app.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (app.label != app.packageName) {
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            modes.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = app.mode == mode,
                    modifier = Modifier.fillMaxHeight(),
                    onClick = { onModeChange(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                    // No check mark: the fill already marks the choice, and the room keeps
                    // "Hide content" readable at large font sizes.
                    icon = {},
                    label = { Text(stringResource(label), maxLines = 2, textAlign = TextAlign.Center) },
                )
            }
        }
    }
}

/** Opens Carom's own notification access page (Android 11+), or the general list. */
private fun openNotificationAccessSettings(context: Context) {
    val component = ComponentName(context, CaromListenerService::class.java)
    val detail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
    } else {
        null
    }
    val list = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    for (intent in listOfNotNull(detail, list)) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
            // Some OEM builds lack the detail page; fall through to the list.
        }
    }
}
