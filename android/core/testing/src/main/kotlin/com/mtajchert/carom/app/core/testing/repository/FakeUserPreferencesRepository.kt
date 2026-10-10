package com.mtajchert.carom.app.core.testing.repository

import com.mtajchert.carom.app.core.data.repository.UserPreferencesRepository
import com.mtajchert.carom.app.core.model.DarkThemeConfig
import com.mtajchert.carom.app.core.model.TaskSortOrder
import com.mtajchert.carom.app.core.model.ThemeBrand
import com.mtajchert.carom.app.core.model.UserPreferences
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Fake implementation of [UserPreferencesRepository] for testing.
 *
 * Similar to [FakeTaskRepository], this provides a controllable
 * implementation for testing ViewModels that depend on user preferences.
 */
class FakeUserPreferencesRepository : UserPreferencesRepository {

    private val preferencesFlow = MutableSharedFlow<UserPreferences>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Current preferences (for test verification).
     */
    val currentPreferences: UserPreferences
        get() = preferencesFlow.replayCache.firstOrNull() ?: UserPreferences()

    init {
        // Initialize with defaults
        preferencesFlow.tryEmit(UserPreferences())
    }

    // =========================================================================
    // Test Control Methods
    // =========================================================================

    /**
     * Set the preferences to return.
     */
    fun setPreferences(preferences: UserPreferences) {
        preferencesFlow.tryEmit(preferences)
    }

    // =========================================================================
    // Repository Implementation
    // =========================================================================

    override val userPreferences: Flow<UserPreferences> = preferencesFlow

    override suspend fun setThemeBrand(themeBrand: ThemeBrand) {
        val current = currentPreferences
        preferencesFlow.tryEmit(current.copy(themeBrand = themeBrand))
    }

    override suspend fun setDarkThemeConfig(darkThemeConfig: DarkThemeConfig) {
        val current = currentPreferences
        preferencesFlow.tryEmit(current.copy(darkThemeConfig = darkThemeConfig))
    }

    override suspend fun setSortOrder(sortOrder: TaskSortOrder) {
        val current = currentPreferences
        preferencesFlow.tryEmit(current.copy(sortOrder = sortOrder))
    }
}
