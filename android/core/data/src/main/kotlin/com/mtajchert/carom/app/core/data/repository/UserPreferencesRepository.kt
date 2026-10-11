package com.mtajchert.carom.app.core.data.repository

import com.mtajchert.carom.app.core.datastore.UserPreferencesDataSource
import com.mtajchert.carom.app.core.model.ActionsFromMac
import com.mtajchert.carom.app.core.model.DarkThemeConfig
import com.mtajchert.carom.app.core.model.ThemeBrand
import com.mtajchert.carom.app.core.model.UserPreferences
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** Non-secret settings (docs/CLIENTS.md). Fake: `FakeUserPreferencesRepository` in core:testing. */
interface UserPreferencesRepository {
    val userPreferences: Flow<UserPreferences>

    suspend fun setThemeBrand(themeBrand: ThemeBrand)
    suspend fun setDarkThemeConfig(darkThemeConfig: DarkThemeConfig)
    suspend fun setDeviceName(name: String)
    suspend fun setForwardingPaused(paused: Boolean)
    suspend fun setForwardOnlyWhenLocked(enabled: Boolean)
    suspend fun setClipboardArrivalNotification(enabled: Boolean)
    suspend fun setOngoingNotification(enabled: Boolean)
    suspend fun setActionsFromMac(mode: ActionsFromMac)
    suspend fun setActionWindowMinutes(minutes: Int)
}

class DefaultUserPreferencesRepository @Inject constructor(
    private val dataSource: UserPreferencesDataSource,
) : UserPreferencesRepository {
    override val userPreferences: Flow<UserPreferences> = dataSource.userPreferences

    override suspend fun setThemeBrand(themeBrand: ThemeBrand) = dataSource.setThemeBrand(themeBrand)
    override suspend fun setDarkThemeConfig(darkThemeConfig: DarkThemeConfig) = dataSource.setDarkThemeConfig(darkThemeConfig)
    override suspend fun setDeviceName(name: String) = dataSource.setDeviceName(name)
    override suspend fun setForwardingPaused(paused: Boolean) = dataSource.setForwardingPaused(paused)
    override suspend fun setForwardOnlyWhenLocked(enabled: Boolean) = dataSource.setForwardOnlyWhenLocked(enabled)
    override suspend fun setClipboardArrivalNotification(enabled: Boolean) = dataSource.setClipboardArrivalNotification(enabled)
    override suspend fun setOngoingNotification(enabled: Boolean) = dataSource.setOngoingNotification(enabled)
    override suspend fun setActionsFromMac(mode: ActionsFromMac) = dataSource.setActionsFromMac(mode)
    override suspend fun setActionWindowMinutes(minutes: Int) = dataSource.setActionWindowMinutes(minutes)
}
