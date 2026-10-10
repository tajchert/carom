package com.mtajchert.carom.app.core.push

import com.mtajchert.carom.app.core.data.runtime.PushStatusRepository
import com.mtajchert.carom.app.core.data.sync.HubProvider
import com.mtajchert.carom.app.core.data.sync.SyncLog
import com.mtajchert.carom.app.core.data.sync.WipeHook
import com.mtajchert.carom.app.core.data.work.SyncWorkScheduler
import com.mtajchert.carom.app.core.hub.HubClient
import com.mtajchert.carom.app.core.hub.HubException
import com.mtajchert.carom.app.core.model.PushState
import com.mtajchert.carom.app.core.security.SecretVault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** A Web Push subscription from the distributor. [toString] hides the endpoint and keys. */
data class PushEndpoint(val url: String, val p256dh: String, val auth: String) {
    override fun toString(): String = "PushEndpoint(…)"
}

/** Why the distributor could not register us. */
enum class PushFailure { NETWORK, ACTION_REQUIRED, VAPID_REQUIRED, INTERNAL }

/** The only seam to the UnifiedPush API, so [PushManager] runs in JVM tests. */
interface PushConnector {
    /** True when any distributor (an installed app, or the embedded FCM one) is available. */
    fun hasDistributor(): Boolean

    /** Keeps the saved distributor or picks one without asking; false when there is none. */
    fun selectDistributor(): Boolean

    /** Asks the distributor for an endpoint; the answer arrives in [CaromPushService]. */
    fun register(vapidPublicKey: String)

    fun unregister()
}

/** Persists the subscription the Hub knows about and the Hub's VAPID key. */
interface PushEndpointStore {
    /** The current endpoint and whether `PUT /v1/devices/self/push` succeeded for it. */
    suspend fun endpoint(): Pair<PushEndpoint, Boolean>?
    suspend fun setEndpoint(endpoint: PushEndpoint?, sentToHub: Boolean = false)
    suspend fun vapidKey(): String?
    suspend fun setVapidKey(key: String)
    suspend fun clear()
}

/**
 * Push registration (docs/CLIENTS.md "Push (phone)", SPEC §9):
 * - registers with the Hub's VAPID key from `GET /v1/info` (cached),
 * - sends each new endpoint with `PUT /v1/devices/self/push` (re-registration on change),
 * - turns the 15-minute fallback poll off only once the Hub has a working endpoint,
 * - deletes the subscription and unregisters on wipe.
 *
 * A push message only schedules an expedited fetch; its payload is never read.
 */
@Singleton
class PushManager @Inject constructor(
    private val connector: PushConnector,
    private val hubProvider: HubProvider,
    private val vault: SecretVault,
    private val store: PushEndpointStore,
    private val scheduler: SyncWorkScheduler,
    private val log: SyncLog,
) : PushStatusRepository, WipeHook {
    private val mutex = Mutex()
    private val stateFlow = MutableStateFlow(PushState.UNKNOWN)
    override val state: StateFlow<PushState> = stateFlow.asStateFlow()

    private suspend fun paired(): Boolean = vault.load().isComplete

    /** Called on app start and after pairing. Safe to call repeatedly. */
    suspend fun ensureRegistered() = mutex.withLock {
        if (!paired()) {
            stateFlow.value = PushState.UNKNOWN
            return@withLock
        }
        if (!connector.selectDistributor()) {
            fallback(PushState.NO_DISTRIBUTOR)
            return@withLock
        }
        val vapid = store.vapidKey() ?: try {
            hubProvider.client()?.info()?.vapidPublicKey?.also { store.setVapidKey(it) }
        } catch (e: HubException) {
            log.log("push_info_failed", e.javaClass.simpleName)
            null
        }
        if (vapid == null) {
            fallback(PushState.FAILED)
            return@withLock
        }
        val saved = store.endpoint()
        when {
            saved == null -> {
                stateFlow.value = PushState.REGISTERING
                scheduler.setPeriodicFetch(true) // until the Hub has an endpoint
            }
            saved.second -> {
                stateFlow.value = PushState.REGISTERED
                scheduler.setPeriodicFetch(false)
            }
            else -> putEndpoint(saved.first) // an earlier PUT failed
        }
        // The distributor answers with the current endpoint, or a new one if it changed.
        connector.register(vapid)
    }

    suspend fun onNewEndpoint(endpoint: PushEndpoint) = mutex.withLock {
        if (!paired()) return@withLock
        val saved = store.endpoint()
        if (saved != null && saved.first == endpoint && saved.second) {
            stateFlow.value = PushState.REGISTERED
            scheduler.setPeriodicFetch(false)
            return@withLock
        }
        store.setEndpoint(endpoint, sentToHub = false)
        putEndpoint(endpoint)
    }

    private suspend fun putEndpoint(endpoint: PushEndpoint) {
        val client = hubProvider.client() ?: return
        try {
            client.putPush(endpoint.url, endpoint.p256dh, endpoint.auth)
            store.setEndpoint(endpoint, sentToHub = true)
            stateFlow.value = PushState.REGISTERED
            scheduler.setPeriodicFetch(false)
            log.log("push_registered", null)
        } catch (e: HubException) {
            log.log("push_put_failed", e.javaClass.simpleName)
            fallback(PushState.FAILED)
        }
    }

    suspend fun onRegistrationFailed(reason: PushFailure) = mutex.withLock {
        log.log("push_registration_failed", reason.name)
        if (paired()) fallback(if (connector.hasDistributor()) PushState.FAILED else PushState.NO_DISTRIBUTOR)
    }

    /** The distributor dropped us (uninstalled, or unlinked by the user). */
    suspend fun onUnregistered() = mutex.withLock {
        if (!paired()) return@withLock
        if (store.endpoint()?.second == true) deleteFromHub(hubProvider.client())
        store.setEndpoint(null)
        fallback(if (connector.hasDistributor()) PushState.FAILED else PushState.NO_DISTRIBUTOR)
    }

    /** A wake-up: fetch right away. */
    fun onMessage() {
        scheduler.scheduleFetch(expedited = true)
    }

    override suspend fun beforeWipe(client: HubClient?) = mutex.withLock {
        if (store.endpoint()?.second == true) deleteFromHub(client)
        connector.unregister()
        store.clear()
        stateFlow.value = PushState.UNKNOWN
    }

    private suspend fun deleteFromHub(client: HubClient?) {
        try {
            client?.deletePush()
        } catch (e: HubException) {
            // A revoked token cannot delete; the Hub dropped the device's subscription anyway.
            log.log("push_delete_failed", e.javaClass.simpleName)
        }
    }

    private fun fallback(state: PushState) {
        stateFlow.value = state
        scheduler.setPeriodicFetch(true)
    }
}
