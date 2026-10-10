package com.mtajchert.carom.app.core.data.sync

import android.os.Build
import android.util.Log
import com.mtajchert.carom.app.core.datastore.UserPreferencesDataSource
import com.mtajchert.carom.app.core.hub.HubClient
import com.mtajchert.carom.app.core.hub.HubClientFactory
import com.mtajchert.carom.app.core.protocol.DeviceKind
import com.mtajchert.carom.app.core.security.SecretVault
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** The client for the Hub this device enrolled with; recreated only when the URL changes. */
@Singleton
class EnrolledHubProvider @Inject constructor(
    private val preferences: UserPreferencesDataSource,
    private val vault: SecretVault,
    private val factory: HubClientFactory,
) : HubProvider {
    @Volatile
    private var cached: HubClient? = null

    override suspend fun client(): HubClient? {
        val url = preferences.userPreferences.first().hubUrl ?: return null
        cached?.takeIf { it.baseUrl == url }?.let { return it }
        return factory.client(url) { vault.load().deviceToken }.also { cached = it }
    }
}

/** This phone's record fields: the user's device name (or the model), the app version and receive rules. */
class AndroidDeviceProfileProvider @Inject constructor(
    private val preferences: UserPreferencesDataSource,
    private val receiveRules: ReceiveRulesStore,
    @param:Named(APP_VERSION) private val appVersion: String,
) : LocalDeviceProfileProvider {
    override suspend fun profile(): LocalDeviceProfile = LocalDeviceProfile(
        name = preferences.userPreferences.first().deviceName?.takeIf { it.isNotBlank() } ?: defaultDeviceName(),
        kind = DeviceKind.PHONE,
        platform = "android",
        appVersion = appVersion,
        receive = receiveRules.receiveRules(),
    )

    companion object {
        /** Qualifier for the app's versionName, bound by :app. */
        const val APP_VERSION = "carom.appVersion"

        fun defaultDeviceName(): String = listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { !it.isNullOrBlank() }
            .joinToString(" ")
            .ifBlank { "Android phone" }
    }
}

/** Clears the enrollment from DataStore after a revocation; theme choices survive. */
class PreferencesStateWiper @Inject constructor(
    private val preferences: UserPreferencesDataSource,
) : LocalStateWiper {
    override suspend fun wipe() = preferences.clearEnrollment()
}

/** Logcat sink. Callers pass codes and ids only; never content, keys, tokens or URIs. */
class LogcatSyncLog @Inject constructor() : SyncLog {
    override fun log(event: String, detail: String?) {
        Log.i(TAG, if (detail == null) event else "$event $detail")
    }

    private companion object {
        const val TAG = "CaromSync"
    }
}
