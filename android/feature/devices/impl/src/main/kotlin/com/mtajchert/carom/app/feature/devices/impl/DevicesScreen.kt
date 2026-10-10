package com.mtajchert.carom.app.feature.devices.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mtajchert.carom.app.core.data.repository.DeviceRepository
import com.mtajchert.carom.app.core.designsystem.component.CaromCard
import com.mtajchert.carom.app.core.model.DeviceKind
import com.mtajchert.carom.app.core.model.DeviceSummary
import com.mtajchert.carom.app.core.ui.CaromSimpleScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@Immutable
data class DevicesUiState(val loading: Boolean = true, val devices: List<DeviceSummary> = emptyList())

/** Read-only for now; rename, remove and the receive matrix come next (docs/CLIENTS.md). */
@HiltViewModel
class DevicesViewModel @Inject constructor(repository: DeviceRepository) : ViewModel() {
    val uiState: StateFlow<DevicesUiState> = repository.devices
        .map { list -> DevicesUiState(loading = false, devices = list.sortedByDescending { it.isSelf }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DevicesUiState())
}

@Composable
fun DevicesScreen(onBack: () -> Unit, viewModel: DevicesViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DevicesContent(state, onBack)
}

@Composable
fun DevicesContent(state: DevicesUiState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    CaromSimpleScreen(title = stringResource(R.string.feature_devices_title), onBack = onBack, modifier = modifier) {
        if (!state.loading && state.devices.isEmpty()) {
            Text(stringResource(R.string.feature_devices_empty), style = MaterialTheme.typography.bodyLarge)
        }
        state.devices.forEach { DeviceRow(it) }
    }
}

@Composable
private fun DeviceRow(device: DeviceSummary) {
    CaromCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val name = device.name ?: stringResource(R.string.feature_devices_unknown, device.fingerprint)
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
            Text(
                text = stringResource(R.string.feature_devices_details, kind, device.fingerprint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
