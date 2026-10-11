package com.mtajchert.carom.app.core.testing.repository

import com.mtajchert.carom.app.core.data.repository.UserPreferencesRepository
import com.mtajchert.carom.app.core.model.ActionsFromMac
import com.mtajchert.carom.app.core.model.DarkThemeConfig
import com.mtajchert.carom.app.core.model.ThemeBrand
import com.mtajchert.carom.app.core.model.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** In-memory [UserPreferencesRepository]; tests set or read [current] directly. */
class FakeUserPreferencesRepository(initial: UserPreferences = UserPreferences()) : UserPreferencesRepository {
    private val state = MutableStateFlow(initial)

    val current: UserPreferences get() = state.value

    fun setPreferences(preferences: UserPreferences) {
        state.value = preferences
    }

    override val userPreferences: Flow<UserPreferences> = state

    override suspend fun setThemeBrand(themeBrand: ThemeBrand) = state.update { it.copy(themeBrand = themeBrand) }
    override suspend fun setDarkThemeConfig(darkThemeConfig: DarkThemeConfig) = state.update { it.copy(darkThemeConfig = darkThemeConfig) }
    override suspend fun setDeviceName(name: String) = state.update { it.copy(deviceName = name) }
    override suspend fun setForwardingPaused(paused: Boolean) = state.update { it.copy(forwardingPaused = paused) }
    override suspend fun setForwardOnlyWhenLocked(enabled: Boolean) = state.update { it.copy(forwardOnlyWhenLocked = enabled) }
    override suspend fun setClipboardArrivalNotification(enabled: Boolean) = state.update { it.copy(clipboardArrivalNotification = enabled) }
    override suspend fun setOngoingNotification(enabled: Boolean) = state.update { it.copy(ongoingNotification = enabled) }
    override suspend fun setActionsFromMac(mode: ActionsFromMac) = state.update { it.copy(actionsFromMac = mode) }
    override suspend fun setActionWindowMinutes(minutes: Int) = state.update {
        it.copy(actionWindowMinutes = ActionsFromMac.windowOrDefault(minutes))
    }
}
