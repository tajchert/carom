package com.mtajchert.carom.app.core.push

import com.mtajchert.carom.app.core.data.sync.HubProvider
import com.mtajchert.carom.app.core.data.sync.SyncLog
import com.mtajchert.carom.app.core.hub.HubClient
import com.mtajchert.carom.app.core.model.PushState
import com.mtajchert.carom.app.core.protocol.B64
import com.mtajchert.carom.app.core.protocol.Identity
import com.mtajchert.carom.app.core.protocol.Kdf
import com.mtajchert.carom.app.core.protocol.Tokens
import com.mtajchert.carom.app.core.security.VaultContents
import com.mtajchert.carom.app.core.testing.hub.FakeHub
import com.mtajchert.carom.app.core.testing.security.InMemorySecretVault
import com.mtajchert.carom.app.core.testing.sync.FakeSyncWorkScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushManagerTest {

    private class FakeConnector(var distributor: Boolean = true) : PushConnector {
        val registered = mutableListOf<String>()
        var unregistered = 0
        override fun hasDistributor() = distributor
        override fun selectDistributor() = distributor
        override fun register(vapidPublicKey: String) {
            registered += vapidPublicKey
        }

        override fun unregister() {
            unregistered++
        }
    }

    private class MemoryStore : PushEndpointStore {
        var value: Pair<PushEndpoint, Boolean>? = null
        var vapid: String? = null
        override suspend fun endpoint() = value
        override suspend fun setEndpoint(endpoint: PushEndpoint?, sentToHub: Boolean) {
            value = endpoint?.let { it to sentToHub }
        }

        override suspend fun vapidKey() = vapid
        override suspend fun setVapidKey(key: String) {
            vapid = key
        }

        override suspend fun clear() {
            value = null
            vapid = null
        }
    }

    private val hub = FakeHub()
    private val connector = FakeConnector()
    private val store = MemoryStore()
    private val scheduler = FakeSyncWorkScheduler()
    private val vault = InMemorySecretVault()
    private var client: HubClient? = null
    private val manager = PushManager(connector, HubProvider { client }, vault, store, scheduler, SyncLog.NONE)
    private val endpoint = PushEndpoint("https://push.example/abc", "p256dh", "auth")

    /** A device set up on the fake Hub, with keys in the vault. */
    private suspend fun pair(): String {
        val identity = Identity.generate()
        val anonymous = hub.factory.client(hub.url) { null }
        val creds = anonymous.setup(hub.setupToken, B64.encode(identity.signPub), B64.encode(identity.kxPub))
        vault.update { VaultContents(identity, creds.token).withGroupKey(1, ByteArray(32), 0) }
        client = hub.factory.client(hub.url) { vault.load().deviceToken }
        return creds.deviceId
    }

    @Test
    fun `registers with the hub vapid key and polls until the endpoint is known`() = runTest {
        pair()
        manager.ensureRegistered()
        assertEquals(listOf("BA"), connector.registered)
        assertEquals(PushState.REGISTERING, manager.state.value)
        assertEquals(true, scheduler.periodic)
        // The key is cached: a second start does not ask the Hub again.
        manager.ensureRegistered()
        assertEquals(1, hub.requests.count { it == "GET /v1/info" })
    }

    @Test
    fun `a new endpoint is sent to the hub and stops polling`() = runTest {
        val id = pair()
        manager.ensureRegistered()
        manager.onNewEndpoint(endpoint)
        assertEquals(FakeHub.PushSubscription(endpoint.url, endpoint.p256dh, endpoint.auth), hub.pushOf(id))
        assertEquals(PushState.REGISTERED, manager.state.value)
        assertEquals(false, scheduler.periodic)
        assertEquals(endpoint to true, store.value)
    }

    @Test
    fun `the same endpoint again is not re-sent, a changed one is`() = runTest {
        val id = pair()
        manager.onNewEndpoint(endpoint)
        manager.onNewEndpoint(endpoint)
        assertEquals(1, hub.requests.count { it == "PUT /v1/devices/self/push" })
        val changed = endpoint.copy(url = "https://push.example/new")
        manager.onNewEndpoint(changed)
        assertEquals(changed.url, hub.pushOf(id)!!.endpoint)
        assertEquals(2, hub.requests.count { it == "PUT /v1/devices/self/push" })
    }

    @Test
    fun `a failed put is retried on the next start`() = runTest {
        val id = pair()
        hub.failures["PUT /v1/devices/self/push"] = FakeHub.networkFailure()
        manager.onNewEndpoint(endpoint)
        assertEquals(PushState.FAILED, manager.state.value)
        assertEquals(true, scheduler.periodic)
        assertEquals(endpoint to false, store.value)

        manager.ensureRegistered()
        assertEquals(endpoint.url, hub.pushOf(id)!!.endpoint)
        assertEquals(PushState.REGISTERED, manager.state.value)
    }

    @Test
    fun `no distributor is exposed and falls back to polling`() = runTest {
        pair()
        connector.distributor = false
        manager.ensureRegistered()
        assertEquals(PushState.NO_DISTRIBUTOR, manager.state.value)
        assertEquals(true, scheduler.periodic)
        assertTrue(connector.registered.isEmpty())
    }

    @Test
    fun `a push message schedules an expedited fetch`() {
        manager.onMessage()
        assertEquals(listOf(true), scheduler.fetches)
    }

    @Test
    fun `nothing registers before pairing`() = runTest {
        manager.ensureRegistered()
        assertEquals(PushState.UNKNOWN, manager.state.value)
        assertTrue(connector.registered.isEmpty())
        assertNull(scheduler.periodic)
    }

    @Test
    fun `wipe deletes the subscription on the hub and unregisters`() = runTest {
        val id = pair()
        manager.ensureRegistered()
        manager.onNewEndpoint(endpoint)
        manager.beforeWipe(client)
        assertNull(hub.pushOf(id))
        assertEquals(1, connector.unregistered)
        assertNull(store.value)
        assertNull(store.vapid)
        assertEquals(PushState.UNKNOWN, manager.state.value)
    }

    @Test
    fun `wipe with a dead token still unregisters locally`() = runTest {
        pair()
        manager.onNewEndpoint(endpoint)
        val dead = hub.factory.client(hub.url) { Tokens.make(Kdf.tokenKey(hub.setupToken), "dev_AAAAAAAAAAAAAAAAAAAAAA") }
        manager.beforeWipe(dead)
        assertEquals(1, connector.unregistered)
        assertNull(store.value)
    }

    @Test
    fun `losing the distributor removes the endpoint from the hub`() = runTest {
        val id = pair()
        manager.onNewEndpoint(endpoint)
        connector.distributor = false
        manager.onUnregistered()
        assertNull(hub.pushOf(id))
        assertEquals(PushState.NO_DISTRIBUTOR, manager.state.value)
        assertEquals(true, scheduler.periodic)
        assertFalse(store.value != null)
    }
}
