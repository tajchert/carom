package com.mtajchert.carom.app.feature.settings.impl

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mtajchert.carom.app.core.data.repository.UserPreferencesRepository
import com.mtajchert.carom.app.core.model.UserPreferences
import com.mtajchert.carom.app.core.ui.CaromSimpleScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The switches every user may need; device name, per-app and clipboard targets come next. */
enum class SettingToggle { PAUSE_FORWARDING, ONLY_WHEN_LOCKED, CLIPBOARD_NOTIFICATION, ONGOING_NOTIFICATION }

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: UserPreferencesRepository,
) : ViewModel() {
    val uiState: StateFlow<UserPreferences?> = preferences.userPreferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun set(toggle: SettingToggle, value: Boolean) {
        viewModelScope.launch {
            when (toggle) {
                SettingToggle.PAUSE_FORWARDING -> preferences.setForwardingPaused(value)
                SettingToggle.ONLY_WHEN_LOCKED -> preferences.setForwardOnlyWhenLocked(value)
                SettingToggle.CLIPBOARD_NOTIFICATION -> preferences.setClipboardArrivalNotification(value)
                SettingToggle.ONGOING_NOTIFICATION -> preferences.setOngoingNotification(value)
            }
        }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val prefs by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsContent(prefs, onBack, viewModel::set)
}

@Composable
fun SettingsContent(
    prefs: UserPreferences?,
    onBack: () -> Unit,
    onToggle: (SettingToggle, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    CaromSimpleScreen(title = stringResource(R.string.feature_settings_title), onBack = onBack, modifier = modifier) {
        if (prefs == null) return@CaromSimpleScreen
        ToggleRow(R.string.feature_settings_pause, prefs.forwardingPaused) { onToggle(SettingToggle.PAUSE_FORWARDING, it) }
        ToggleRow(R.string.feature_settings_only_locked, prefs.forwardOnlyWhenLocked) { onToggle(SettingToggle.ONLY_WHEN_LOCKED, it) }
        ToggleRow(R.string.feature_settings_clipboard_notification, prefs.clipboardArrivalNotification) {
            onToggle(SettingToggle.CLIPBOARD_NOTIFICATION, it)
        }
        ToggleRow(R.string.feature_settings_ongoing, prefs.ongoingNotification) { onToggle(SettingToggle.ONGOING_NOTIFICATION, it) }
    }
}

@Composable
private fun ToggleRow(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}
