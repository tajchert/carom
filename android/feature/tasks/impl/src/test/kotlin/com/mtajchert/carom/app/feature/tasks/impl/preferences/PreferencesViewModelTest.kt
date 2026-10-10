package com.mtajchert.carom.app.feature.tasks.impl.preferences

import com.mtajchert.carom.app.core.data.repository.UserPreferencesRepository
import com.mtajchert.carom.app.core.model.DarkThemeConfig
import com.mtajchert.carom.app.core.model.TaskSortOrder
import com.mtajchert.carom.app.core.model.ThemeBrand
import com.mtajchert.carom.app.core.testing.repository.FakeUserPreferencesRepository
import com.mtajchert.carom.app.core.testing.util.MainDispatcherRule
import com.mtajchert.carom.app.feature.tasks.impl.TaskUiError
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PreferencesViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun `theme and sorting changes are observed from the repository`() = runTest {
        val repository = FakeUserPreferencesRepository()
        val viewModel = PreferencesViewModel(repository)
        viewModel.setDarkTheme(DarkThemeConfig.DARK)
        viewModel.setDynamicColors(true)
        viewModel.setSortOrder(TaskSortOrder.ALPHABETICAL)
        assertEquals(DarkThemeConfig.DARK, viewModel.uiState.value.preferences.darkThemeConfig)
        assertEquals(ThemeBrand.DYNAMIC, viewModel.uiState.value.preferences.themeBrand)
        assertEquals(TaskSortOrder.ALPHABETICAL, viewModel.uiState.value.preferences.sortOrder)
    }

    @Test fun `retrying a failed preference read clears the error after recovery`() = runTest {
        val stored = FakeUserPreferencesRepository()
        var fail = true
        val repository = object : UserPreferencesRepository by stored {
            override val userPreferences = kotlinx.coroutines.flow.flow {
                if (fail) throw java.io.IOException("Read failed")
                emit(stored.currentPreferences)
            }
        }
        val viewModel = PreferencesViewModel(repository)
        assertEquals(TaskUiError.PreferencesLoadFailed, viewModel.uiState.value.error)
        fail = false
        viewModel.reload()
        assertNull(viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test fun `failed preference write retains state and can be retried`() = runTest {
        val stored = FakeUserPreferencesRepository()
        var fail = true
        val repository = object : UserPreferencesRepository by stored {
            override suspend fun setDarkThemeConfig(darkThemeConfig: DarkThemeConfig) {
                if (fail) throw java.io.IOException("Disk unavailable")
                stored.setDarkThemeConfig(darkThemeConfig)
            }
        }
        val viewModel = PreferencesViewModel(repository)
        viewModel.setDarkTheme(DarkThemeConfig.DARK)
        assertEquals(DarkThemeConfig.FOLLOW_SYSTEM, viewModel.uiState.value.preferences.darkThemeConfig)
        assertEquals(TaskUiError.PreferencesSaveFailed, viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.isSaving)
        fail = false
        viewModel.setDarkTheme(DarkThemeConfig.DARK)
        assertEquals(DarkThemeConfig.DARK, viewModel.uiState.value.preferences.darkThemeConfig)
        assertNull(viewModel.uiState.value.error)
    }
}
