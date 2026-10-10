package com.mtajchert.carom.app.core.push

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mtajchert.carom.app.core.data.runtime.SyncStartHook
import com.mtajchert.carom.app.core.security.SecretVault
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushMessage
import org.unifiedpush.android.connector.data.ResolvedDistributor
import javax.inject.Inject
import javax.inject.Singleton
import org.unifiedpush.android.connector.data.PushEndpoint as UpEndpoint

/** [PushConnector] over UnifiedPush connector 3.x. One registration instance per app. */
class UnifiedPushConnector @Inject constructor(
    @ApplicationContext private val context: Context,
) : PushConnector {
    override fun hasDistributor(): Boolean = UnifiedPush.getDistributors(context).isNotEmpty()

    override fun selectDistributor(): Boolean {
        val distributors = UnifiedPush.getDistributors(context)
        if (distributors.isEmpty()) return false
        val saved = UnifiedPush.getSavedDistributor(context)
        if (saved != null && saved in distributors) return true
        // No UI here: use the system default, else our embedded FCM distributor, else the first one.
        val pick = when (val resolved = UnifiedPush.resolveDefaultDistributor(context)) {
            is ResolvedDistributor.Found -> resolved.packageName
            else -> context.packageName.takeIf { it in distributors } ?: distributors.first()
        }
        UnifiedPush.saveDistributor(context, pick)
        return true
    }

    override fun register(vapidPublicKey: String) {
        UnifiedPush.register(context, INSTANCE, null, vapidPublicKey)
    }

    override fun unregister() {
        UnifiedPush.unregister(context, INSTANCE)
    }

    private companion object {
        const val INSTANCE = "carom"
    }
}

/** [PushEndpointStore] in the app's Preferences DataStore (`push_` keys; excluded from backup like the rest). */
@Singleton
class DataStorePushEndpointStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : PushEndpointStore {
    @Serializable
    private data class EndpointDto(val url: String, val p256dh: String, val auth: String)

    override suspend fun endpoint(): Pair<PushEndpoint, Boolean>? {
        val prefs = dataStore.data.first()
        val dto = prefs[ENDPOINT]?.let { runCatching { Json.decodeFromString(EndpointDto.serializer(), it) }.getOrNull() } ?: return null
        return PushEndpoint(dto.url, dto.p256dh, dto.auth) to (prefs[SENT] ?: false)
    }

    override suspend fun setEndpoint(endpoint: PushEndpoint?, sentToHub: Boolean) {
        dataStore.edit {
            if (endpoint == null) {
                it.remove(ENDPOINT)
                it.remove(SENT)
            } else {
                it[ENDPOINT] = Json.encodeToString(EndpointDto.serializer(), EndpointDto(endpoint.url, endpoint.p256dh, endpoint.auth))
                it[SENT] = sentToHub
            }
        }
    }

    override suspend fun vapidKey(): String? = dataStore.data.first()[VAPID]

    override suspend fun setVapidKey(key: String) {
        dataStore.edit { it[VAPID] = key }
    }

    override suspend fun clear() {
        dataStore.edit {
            it.remove(ENDPOINT)
            it.remove(SENT)
            it.remove(VAPID)
        }
    }

    private companion object {
        val ENDPOINT = stringPreferencesKey("push_endpoint")
        val SENT = booleanPreferencesKey("push_endpoint_sent")
        val VAPID = stringPreferencesKey("push_vapid_key")
    }
}

/** Registers on app start and again whenever this device becomes paired. */
class PushStartHook @Inject constructor(
    private val manager: PushManager,
    private val vault: SecretVault,
) : SyncStartHook {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onAppStart() {
        scope.launch {
            vault.load()
            vault.contents
                .map { contents -> contents?.takeIf { it.isComplete }?.identity?.deviceId }
                .distinctUntilChanged()
                .collect { manager.ensureRegistered() }
        }
    }
}

/**
 * The connector's [PushService]. Events are handed to [PushManager] on a process-wide scope so a
 * `PUT /v1/devices/self/push` finishes even after the distributor unbinds. Nothing here logs the
 * endpoint, keys or message bytes.
 */
@AndroidEntryPoint
class CaromPushService : PushService() {
    @Inject
    lateinit var manager: PushManager

    override fun onNewEndpoint(endpoint: UpEndpoint, instance: String) {
        val keys = endpoint.pubKeySet ?: return
        scope.launch { manager.onNewEndpoint(PushEndpoint(endpoint.url, keys.pubKey, keys.auth)) }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        manager.onMessage()
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        val failure = when (reason) {
            FailedReason.NETWORK -> PushFailure.NETWORK
            FailedReason.ACTION_REQUIRED -> PushFailure.ACTION_REQUIRED
            FailedReason.VAPID_REQUIRED -> PushFailure.VAPID_REQUIRED
            else -> PushFailure.INTERNAL
        }
        scope.launch { manager.onRegistrationFailed(failure) }
    }

    override fun onUnregistered(instance: String) {
        scope.launch { manager.onUnregistered() }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
