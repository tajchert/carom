package com.mtajchert.carom.app.feature.home.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtajchert.carom.app.core.designsystem.component.CaromButton
import com.mtajchert.carom.app.core.designsystem.component.CaromCard
import com.mtajchert.carom.app.core.designsystem.component.CaromLoadingIndicator
import com.mtajchert.carom.app.core.designsystem.component.CaromOutlinedButton
import com.mtajchert.carom.app.core.designsystem.component.CaromTopAppBar
import com.mtajchert.carom.app.core.model.RecentItem
import com.mtajchert.carom.app.core.model.RecentItemKind
import com.mtajchert.carom.app.core.ui.ConnectionStatusIndicator

@Composable
fun HomeScreen(
    onSetUp: () -> Unit,
    onAddDevice: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.refresh() }
    HomeContent(
        state = state,
        onSetUp = onSetUp,
        onAddDevice = onAddDevice,
        onOpenDevices = onOpenDevices,
        onOpenSettings = onOpenSettings,
    )
}

@Composable
fun HomeContent(
    state: HomeUiState,
    onSetUp: () -> Unit,
    onAddDevice: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            CaromTopAppBar(
                title = stringResource(R.string.feature_home_title),
                actions = {
                    if (state is HomeUiState.Paired) {
                        IconButton(onClick = onOpenDevices) {
                            Icon(Icons.Outlined.Devices, contentDescription = stringResource(R.string.feature_home_devices))
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
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
                HomeUiState.NotPaired -> NotPaired(onSetUp)
                is HomeUiState.Paired -> Paired(state, onAddDevice)
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
private fun Paired(state: HomeUiState.Paired, onAddDevice: () -> Unit) {
    Column(
        modifier = Modifier
            .widthIn(max = 600.dp)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CaromCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = state.deviceName ?: stringResource(R.string.feature_home_this_device),
                    style = MaterialTheme.typography.titleMedium,
                )
                ConnectionStatusIndicator(state.connection)
                Text(
                    text = stringResource(R.string.feature_home_hub, state.hubHost),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.pendingMessages > 0) {
                    Text(
                        text = pluralStringResource(R.plurals.feature_home_pending, state.pendingMessages, state.pendingMessages),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        CaromOutlinedButton(onClick = onAddDevice, text = stringResource(R.string.feature_home_add_device), modifier = Modifier.fillMaxWidth())
        if (state.recent.isNotEmpty()) {
            Text(stringResource(R.string.feature_home_recent), style = MaterialTheme.typography.titleSmall)
            state.recent.forEach { RecentRow(it) }
        }
    }
}

@Composable
private fun RecentRow(item: RecentItem) {
    val text = when (item.kind) {
        RecentItemKind.CLIPBOARD_SENT -> stringResource(R.string.feature_home_recent_clipboard_sent, item.summary)
        RecentItemKind.CLIPBOARD_RECEIVED -> stringResource(R.string.feature_home_recent_clipboard_received, item.summary)
        RecentItemKind.DEVICE_REMOVED -> stringResource(R.string.feature_home_recent_device_removed, item.summary)
    }
    Text(text = text, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
}
