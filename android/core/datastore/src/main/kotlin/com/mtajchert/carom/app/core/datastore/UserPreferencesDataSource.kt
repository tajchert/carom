package com.mtajchert.carom.app.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mtajchert.carom.app.core.model.ActionsFromMac
import com.mtajchert.carom.app.core.model.DarkThemeConfig
import com.mtajchert.carom.app.core.model.ThemeBrand
import com.mtajchert.carom.app.core.model.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject

/**
 * Non-secret settings and small sync state in Preferences DataStore.
 *
 * The file lives in the app's files dir, which `backup_rules.xml` and
 * `data_extraction_rules.xml` exclude together with everything else.
 */
class UserPreferencesDataSource @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val data: Flow<Preferences> = dataStore.data.catch { throwable ->
        // A transient read failure must not brick startup; other failures are bugs.
        if (throwable is IOException) emit(emptyPreferences()) else throw throwable
    }

    val userPreferences: Flow<UserPreferences> = data.map { p ->
        UserPreferences(
            themeBrand = p[THEME_BRAND]?.let { enumValueOrNull<ThemeBrand>(it) } ?: ThemeBrand.DEFAULT,
            darkThemeConfig = p[DARK_THEME_CONFIG]?.let { enumValueOrNull<DarkThemeConfig>(it) }
                ?: DarkThemeConfig.FOLLOW_SYSTEM,
            hubUrl = p[HUB_URL],
            deviceId = p[DEVICE_ID],
            deviceName = p[DEVICE_NAME],
            forwardingPaused = p[FORWARDING_PAUSED] ?: false,
            forwardOnlyWhenLocked = p[FORWARD_ONLY_WHEN_LOCKED] ?: false,
            clipboardArrivalNotification = p[CLIPBOARD_ARRIVAL_NOTIFICATION] ?: true,
            ongoingNotification = p[ONGOING_NOTIFICATION] ?: false,
            actionsFromMac = p[ACTIONS_FROM_MAC]?.let { enumValueOrNull<ActionsFromMac>(it) } ?: ActionsFromMac.AFTER_NOTIFICATION,
            actionWindowMinutes = ActionsFromMac.windowOrDefault(p[ACTION_WINDOW_MINUTES]),
        )
    }

    suspend fun setThemeBrand(themeBrand: ThemeBrand) = edit { it[THEME_BRAND] = themeBrand.name }

    suspend fun setDarkThemeConfig(config: DarkThemeConfig) = edit { it[DARK_THEME_CONFIG] = config.name }

    /** Stores where this device is enrolled. Pass nulls to forget (after a wipe). */
    suspend fun setEnrollment(hubUrl: String?, deviceId: String?) = edit {
        if (hubUrl == null) it.remove(HUB_URL) else it[HUB_URL] = hubUrl
        if (deviceId == null) it.remove(DEVICE_ID) else it[DEVICE_ID] = deviceId
    }

    suspend fun setDeviceName(name: String) = edit { it[DEVICE_NAME] = name }

    suspend fun setForwardingPaused(value: Boolean) = edit { it[FORWARDING_PAUSED] = value }

    suspend fun setForwardOnlyWhenLocked(value: Boolean) = edit { it[FORWARD_ONLY_WHEN_LOCKED] = value }

    suspend fun setClipboardArrivalNotification(value: Boolean) = edit { it[CLIPBOARD_ARRIVAL_NOTIFICATION] = value }

    suspend fun setOngoingNotification(value: Boolean) = edit { it[ONGOING_NOTIFICATION] = value }

    suspend fun setActionsFromMac(mode: ActionsFromMac) = edit { it[ACTIONS_FROM_MAC] = mode.name }

    suspend fun setActionWindowMinutes(minutes: Int) = edit {
        it[ACTION_WINDOW_MINUTES] = ActionsFromMac.windowOrDefault(minutes)
    }

    /** `ts` of the last clipboard item applied locally (SPEC §3.3 rule 6). */
    suspend fun lastClipboardTs(): Long? = data.first()[LAST_CLIPBOARD_TS]

    suspend fun setLastClipboardTs(ts: Long) = edit { it[LAST_CLIPBOARD_TS] = ts }

    /** Forgets everything tied to the group (wipe/revocation). Theme choices survive. */
    suspend fun clearEnrollment() = edit {
        it.remove(HUB_URL)
        it.remove(DEVICE_ID)
        it.remove(LAST_CLIPBOARD_TS)
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit { block(it) }
    }

    companion object {
        private val THEME_BRAND = stringPreferencesKey("theme_brand")
        private val DARK_THEME_CONFIG = stringPreferencesKey("dark_theme_config")
        private val HUB_URL = stringPreferencesKey("hub_url")
        private val DEVICE_ID = stringPreferencesKey("device_id")
        private val DEVICE_NAME = stringPreferencesKey("device_name")
        private val FORWARDING_PAUSED = booleanPreferencesKey("forwarding_paused")
        private val FORWARD_ONLY_WHEN_LOCKED = booleanPreferencesKey("forward_only_when_locked")
        private val CLIPBOARD_ARRIVAL_NOTIFICATION = booleanPreferencesKey("clipboard_arrival_notification")
        private val ONGOING_NOTIFICATION = booleanPreferencesKey("ongoing_notification")
        private val ACTIONS_FROM_MAC = stringPreferencesKey("actions_from_mac")
        private val ACTION_WINDOW_MINUTES = intPreferencesKey("action_window_minutes")
        private val LAST_CLIPBOARD_TS = longPreferencesKey("last_clipboard_ts")
    }
}

/**
 * Reads persisted enum names defensively. Preferences can outlive enum renames and downgrades,
 * so unknown values must not make the whole stream fail.
 */
private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? =
    enumValues<T>().firstOrNull { it.name == value }
