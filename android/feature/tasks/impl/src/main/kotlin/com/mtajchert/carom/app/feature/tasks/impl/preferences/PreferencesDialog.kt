package com.mtajchert.carom.app.feature.tasks.impl.preferences

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtajchert.carom.app.core.model.DarkThemeConfig
import com.mtajchert.carom.app.core.model.TaskSortOrder
import com.mtajchert.carom.app.core.model.ThemeBrand
import com.mtajchert.carom.app.feature.tasks.impl.R

@Composable
fun PreferencesDialog(onDismiss: () -> Unit, viewModel: PreferencesViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PreferencesDialogContent(state, onDismiss, viewModel::setDarkTheme, viewModel::setDynamicColors,
        viewModel::setSortOrder, viewModel::reload)
}

@Composable
internal fun PreferencesDialogContent(
    state: PreferencesUiState,
    onDismiss: () -> Unit,
    onTheme: (DarkThemeConfig) -> Unit,
    onDynamicColors: (Boolean) -> Unit,
    onSort: (TaskSortOrder) -> Unit,
    onRetry: () -> Unit,
) {
    val enabled = !state.isLoading && !state.isSaving
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.preferences)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.theme), style = MaterialTheme.typography.titleSmall)
                Column(Modifier.selectableGroup()) {
                    DarkThemeConfig.entries.forEach { config ->
                        Choice(stringResource(when (config) {
                            DarkThemeConfig.FOLLOW_SYSTEM -> R.string.theme_system
                            DarkThemeConfig.LIGHT -> R.string.theme_light
                            DarkThemeConfig.DARK -> R.string.theme_dark
                        }), state.preferences.darkThemeConfig == config, enabled) { onTheme(config) }
                    }
                }
                Row(Modifier.fillMaxWidth().selectable(
                    selected = state.preferences.themeBrand == ThemeBrand.DYNAMIC,
                    enabled = enabled, role = Role.Checkbox,
                    onClick = { onDynamicColors(state.preferences.themeBrand != ThemeBrand.DYNAMIC) },
                ), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = state.preferences.themeBrand == ThemeBrand.DYNAMIC,
                        onCheckedChange = null, enabled = enabled)
                    Text(stringResource(R.string.dynamic_colors))
                }
                Text(stringResource(R.string.sort_order), style = MaterialTheme.typography.titleSmall)
                Column(Modifier.selectableGroup()) {
                    TaskSortOrder.entries.forEach { order ->
                        Choice(stringResource(when (order) {
                            TaskSortOrder.CREATED_DATE -> R.string.sort_date
                            TaskSortOrder.PRIORITY -> R.string.sort_priority
                            TaskSortOrder.ALPHABETICAL -> R.string.sort_alpha
                        }), state.preferences.sortOrder == order, enabled) { onSort(order) }
                    }
                }
                state.error?.let { error ->
                    Text(error.message(), color = MaterialTheme.colorScheme.error)
                    if (error == com.mtajchert.carom.app.feature.tasks.impl.TaskUiError.PreferencesLoadFailed) {
                        TextButton(onClick = onRetry) { Text(stringResource(com.mtajchert.carom.app.core.designsystem.R.string.retry)) }
                    }
                }
            }
        },
    )
}

@Composable
private fun Choice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled,
        role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label)
    }
}
