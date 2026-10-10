package com.mtajchert.carom.app.feature.tasks.impl.preferences

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mtajchert.carom.app.core.data.repository.UserPreferencesRepository
import com.mtajchert.carom.app.core.model.DarkThemeConfig
import com.mtajchert.carom.app.core.model.TaskSortOrder
import com.mtajchert.carom.app.core.model.ThemeBrand
import com.mtajchert.carom.app.core.model.UserPreferences
import com.mtajchert.carom.app.feature.tasks.impl.TaskUiError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PreferencesUiState(
    val preferences: UserPreferences = UserPreferences(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: TaskUiError? = null,
)

/** Writes are awaited; the DataStore stream remains the source of truth. */
@HiltViewModel
class PreferencesViewModel @Inject constructor(
    private val repository: UserPreferencesRepository,
) : ViewModel() {
    private val state = MutableStateFlow(PreferencesUiState())
    val uiState = state.asStateFlow()

    private var observation: Job? = null

    init { reload() }

    fun reload() {
        observation?.cancel()
        state.update { it.copy(isLoading = true, error = null) }
        observation = viewModelScope.launch {
            try {
                repository.userPreferences.collect { preferences ->
                    state.update { it.copy(preferences = preferences, isLoading = false) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                state.update { it.copy(isLoading = false, error = TaskUiError.PreferencesLoadFailed) }
            }
        }
    }

    fun setDarkTheme(config: DarkThemeConfig) = save { repository.setDarkThemeConfig(config) }
    fun setDynamicColors(enabled: Boolean) = save {
        repository.setThemeBrand(if (enabled) ThemeBrand.DYNAMIC else ThemeBrand.DEFAULT)
    }
    fun setSortOrder(order: TaskSortOrder) = save { repository.setSortOrder(order) }

    private fun save(write: suspend () -> Unit) {
        if (state.value.isLoading || state.value.isSaving) return
        state.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            try { write() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                state.update { it.copy(error = TaskUiError.PreferencesSaveFailed) }
            } finally {
                state.update { it.copy(isSaving = false) }
            }
        }
    }
}
