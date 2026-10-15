package com.mtajchert.carom.app.feature.notifications.impl.apps

import com.mtajchert.carom.app.core.designsystem.component.CaromCard
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.mtajchert.carom.app.core.designsystem.component.CaromGroup
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.BottomAppBar
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.core.graphics.drawable.toBitmap
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtajchert.carom.app.core.designsystem.component.CaromButton
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
        onFilterChange = viewModel::setFilter,
        onExpandedChange = viewModel::setExpanded,
        onModeChange = viewModel::setMode,
        onForwardOngoingChange = viewModel::setForwardOngoing,
        onToggleSelected = viewModel::toggleSelected,
        onToggleSelectAll = viewModel::toggleSelectAll,
        onClearSelection = viewModel::clearSelection,
        onModeForSelected = viewModel::setModeForSelected,
        onOpenAccessSettings = { openNotificationAccessSettings(context) },
    )
}

/**
 * Stateless "Apps" screen: search, filters, and one Forward / Hide content / Off choice per app.
 * Long-pressing a row starts selection mode, where the bottom bar applies one choice to every selected app.
 */
@Composable
fun AppsContent(
    state: AppsUiState,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onFilterChange: (AppsFilter) -> Unit,
    onModeChange: (String, AppForwardingMode) -> Unit,
    onForwardOngoingChange: (String, Boolean) -> Unit,
    onOpenAccessSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onExpandedChange: (String?) -> Unit = {},
    onToggleSelected: (String) -> Unit = {},
    onToggleSelectAll: () -> Unit = {},
    onClearSelection: () -> Unit = {},
    onModeForSelected: (AppForwardingMode) -> Unit = {},
) {
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val selecting = state.selected.isNotEmpty()
    BackHandler(enabled = selecting, onBack = onClearSelection)
    Scaffold(
        modifier = modifier,
        topBar = {
            if (selecting) {
                CaromTopAppBar(
                    title = pluralStringResource(R.plurals.feature_notifications_selected_count, state.selected.size, state.selected.size),
                    onNavigationClick = onClearSelection,
                    navigationIcon = Icons.Filled.Close,
                    navigationIconContentDescription = stringResource(R.string.feature_notifications_clear_selection),
                    actions = {
                        IconButton(onClick = onToggleSelectAll) {
                            Icon(Icons.Filled.SelectAll, contentDescription = stringResource(R.string.feature_notifications_select_all))
                        }
                    },
                )
            } else {
                CaromTopAppBar(title = stringResource(R.string.feature_notifications_apps_title), onNavigationClick = onBack)
            }
        },
        bottomBar = {
            if (selecting) BulkModeBar(onModeForSelected)
        },
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
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!state.accessGranted) {
                    item(key = "access") { AccessCard(onOpenAccessSettings) }
                }
                if (state.totalApps == 0) {
                    item(key = "empty") { EmptyApps() }
                } else {
                    item(key = "search") { SearchField(state.query, onQueryChange) }
                    item(key = "filters") {
                        FilterRow(state) {
                            expanded = null
                            onFilterChange(it)
                        }
                    }
                    if (state.apps.isEmpty()) {
                        item(key = "no_results") {
                            Text(
                                stringResource(
                                    if (state.filter != AppsFilter.ALL && state.query.isBlank()) {
                                        R.string.feature_notifications_no_results_filter
                                    } else {
                                        R.string.feature_notifications_no_results
                                    },
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    }
                    items(state.apps, key = { it.packageName }) { app ->
                        AppRow(
                            app,
                            expanded = !selecting && expanded == app.packageName,
                            selection = if (selecting) app.packageName in state.selected else null,
                            onToggleSelected = {
                                if (expanded != null) {
                                    expanded = null
                                    onExpandedChange(null)
                                }
                                onToggleSelected(app.packageName)
                            },
                            onToggleExpanded = {
                                expanded = if (expanded == app.packageName) null else app.packageName
                                onExpandedChange(expanded)
                            },
                            onModeChange = { onModeChange(app.packageName, it) },
                            onForwardOngoingChange = { onForwardOngoingChange(app.packageName, it) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AccessCard(onOpenAccessSettings: () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                stringResource(R.string.feature_notifications_access_missing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            CaromButton(
                onClick = onOpenAccessSettings,
                text = stringResource(R.string.feature_notifications_access_action),
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = 16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        singleLine = true,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
        ),
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
private fun FilterRow(state: AppsUiState, onFilterChange: (AppsFilter) -> Unit) {
    val filters = listOf(
        Triple(AppsFilter.ALL, R.string.feature_notifications_filter_all, state.totalApps),
        Triple(AppsFilter.FORWARD, R.string.feature_notifications_mode_forward, state.forwardCount),
        Triple(AppsFilter.HIDDEN, R.string.feature_notifications_filter_hidden, state.hiddenCount),
        Triple(AppsFilter.OFF, R.string.feature_notifications_mode_off, state.offCount),
    )
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        filters.forEach { (filter, label, count) ->
            val selected = state.filter == filter
            FilterChip(
                selected = selected,
                onClick = { onFilterChange(filter) },
                label = { Text(stringResource(R.string.feature_notifications_filter_chip, stringResource(label), count)) },
                leadingIcon = if (selected) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                } else {
                    null
                },
                shape = RoundedCornerShape(12.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }
    }
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

/** Forward / Hide content / Off for every selected app. */
@Composable
private fun BulkModeBar(onModeForSelected: (AppForwardingMode) -> Unit) {
    BottomAppBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        modes.forEach { (mode, label) ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(role = Role.Button) { onModeForSelected(mode) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(mode.icon(), contentDescription = null)
                Text(stringResource(label), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2)
            }
        }
    }
}

private fun AppForwardingMode.icon(): ImageVector = when (this) {
    AppForwardingMode.FORWARD -> Icons.AutoMirrored.Filled.ArrowForward
    AppForwardingMode.HIDE_CONTENT -> Icons.Filled.VisibilityOff
    AppForwardingMode.OFF -> Icons.Filled.Block
}

/** [selection] is null outside selection mode, else whether this row is selected. */
@Composable
private fun AppRow(
    app: NotificationAppSetting,
    expanded: Boolean,
    selection: Boolean?,
    onToggleSelected: () -> Unit,
    onToggleExpanded: () -> Unit,
    onModeChange: (AppForwardingMode) -> Unit,
    onForwardOngoingChange: (Boolean) -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    val expandedState = stringResource(
        if (expanded) R.string.feature_notifications_state_expanded else R.string.feature_notifications_state_collapsed,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (expanded) {
                    Modifier
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                } else {
                    Modifier
                },
            ),
    ) {
        val selectLabel = stringResource(R.string.feature_notifications_select)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (selection == null) {
                        Modifier
                            .semantics { stateDescription = expandedState }
                            .combinedClickable(
                                onClickLabel = stringResource(R.string.feature_notifications_change_forwarding),
                                role = Role.Button,
                                onLongClickLabel = selectLabel,
                                onLongClick = onToggleSelected,
                                onClick = onToggleExpanded,
                            )
                    } else {
                        Modifier.toggleable(value = selection, role = Role.Checkbox, onValueChange = { onToggleSelected() })
                    },
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (selection == null) AppAvatar(app) else SelectionMark(selection)
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (app.label != app.packageName) {
                    Text(
                        app.packageName,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (expanded) {
                Icon(Icons.Filled.ExpandLess, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                StateChip(app.mode)
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    modes.forEachIndexed { index, (mode, label) ->
                        SegmentedButton(
                            selected = app.mode == mode,
                            modifier = Modifier.fillMaxHeight(),
                            onClick = { onModeChange(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                            label = { Text(stringResource(label), maxLines = 2, textAlign = TextAlign.Center) },
                        )
                    }
                }
                ToggleRow(
                    label = stringResource(
                        if (app.forwardOngoing) R.string.feature_notifications_ongoing_forward else R.string.feature_notifications_ongoing_off,
                    ),
                    checked = app.forwardOngoing,
                    onChange = onForwardOngoingChange,
                )
            }
        }
    }
}

/** Stands in for the app icon in selection mode: a filled check when selected, an empty ring otherwise. */
@Composable
private fun SelectionMark(selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .then(
                if (selected) Modifier.background(colors.primary) else Modifier.border(2.dp, colors.outline, CircleShape),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = colors.onPrimary)
    }
}

/** The app's launcher icon when the package is installed, else its first letter. */
@Composable
private fun AppAvatar(app: NotificationAppSetting) {
    val context = LocalContext.current
    val icon = remember(app.packageName) {
        runCatching { context.packageManager.getApplicationIcon(app.packageName).toBitmap().asImageBitmap() }.getOrNull()
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Image(icon, contentDescription = null, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                app.label.firstOrNull { it.isLetterOrDigit() }?.uppercase().orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One-word state: icon plus label, never colour alone. */
@Composable
private fun StateChip(mode: AppForwardingMode) {
    val icon = mode.icon()
    val label = when (mode) {
        AppForwardingMode.FORWARD -> R.string.feature_notifications_mode_forward
        AppForwardingMode.HIDE_CONTENT -> R.string.feature_notifications_chip_hide
        AppForwardingMode.OFF -> R.string.feature_notifications_mode_off
    }
    val shape = CircleShape
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .clip(shape)
            .then(
                when (mode) {
                    AppForwardingMode.FORWARD -> Modifier.background(colors.primaryContainer)
                    AppForwardingMode.HIDE_CONTENT -> Modifier.background(colors.surfaceContainerHighest)
                    AppForwardingMode.OFF -> Modifier.border(1.dp, colors.outline, shape)
                },
            )
            .padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val tint = if (mode == AppForwardingMode.FORWARD) colors.onPrimaryContainer else colors.onSurfaceVariant
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = tint)
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
