package com.mtajchert.carom.app.core.hub

import com.mtajchert.carom.app.core.protocol.Box
import com.mtajchert.carom.app.core.protocol.Envelopes
import com.mtajchert.carom.app.core.protocol.Identity
import com.mtajchert.carom.app.core.protocol.Payload
import com.mtajchert.carom.app.core.protocol.UuidV7Generator
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class OkHttpHubClientTest {
    private val server = MockWebServer()
    private val http = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
    private var token: String? = "ct1.token"
    private lateinit var client: HubClient

    @Before
    fun setUp() {
        server.start()
        // A Hub mounted under a path prefix must keep that prefix.
        client = OkHttpHubClientFactory(http).client(server.url("/carom/").toString()) { token }
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `info needs no auth and parses every field`() = runTest {
        server.enqueue(json(200, """{"name":"carom-hub","protocol":1,"version":"0.1.0","setup_done":true,"vapid_public_key":"BA","jurisdiction":null,"extra":1}"""))
        val info = client.info()
        assertEquals(HubInfo("carom-hub", 1, "0.1.0", true, "BA", null), info)
        val request = take()
        assertEquals("GET", request.method)
        assertEquals("/carom/v1/info", request.url.encodedPath)
        assertNull(request.headers["Authorization"])
    }

    @Test
    fun `setup posts keys and returns credentials without printing the token`() = runTest {
        server.enqueue(json(200, """{"device_id":"dev_x","token":"ct1.secret"}"""))
        val creds = client.setup("s".repeat(32), "signpub", "kxpub")
        assertEquals("ct1.secret", creds.token)
        assertFalse(creds.toString().contains("secret"))
        val body = body(take())
        assertEquals("signpub", body["sign_pub"]!!.jsonPrimitive.content)
        assertEquals("kxpub", body["kx_pub"]!!.jsonPrimitive.content)
    }

    @Test
    fun `authenticated routes send the bearer token`() = runTest {
        server.enqueue(json(200, """{"devices":[{"id":"dev_a","sign_pub":"s","kx_pub":"k","created_at":1,"last_seen":2,"has_push":true,"record":{"id":"dev_a"}}]}"""))
        val devices = client.devices()
        assertEquals(1, devices.size)
        assertTrue(devices[0].hasPush)
        assertEquals("Bearer ct1.token", take().headers["Authorization"])
    }

    @Test
    fun `post message conflict carries the inactive ids`() = runTest {
        server.enqueue(json(409, """{"error":{"code":"conflict","message":"some recipients are not active devices"},"inactive":["dev_b"]}"""))
        try {
            client.postMessage(envelope())
            fail()
        } catch (e: HubException.Http) {
            assertEquals(409, e.status)
            assertEquals(HubErrorCode.CONFLICT, e.code)
            assertEquals(listOf("dev_b"), e.inactive)
            assertFalse(e.isRetryable)
        }
        val body = body(take())
        assertTrue(body["envelope"] is JsonObject)
    }

    @Test
    fun `full queues are reported and are not retryable`() = runTest {
        server.enqueue(json(429, """{"error":{"code":"rate_limited","message":"full"},"full":["dev_c"]}"""))
        try {
            client.postMessage(envelope())
            fail()
        } catch (e: HubException.Http) {
            assertEquals(listOf("dev_c"), e.full)
            assertFalse(e.isRetryable)
        }
    }

    @Test
    fun `superseded and duplicate posts parse`() = runTest {
        server.enqueue(json(200, """{"seq":null,"duplicate":false,"superseded":true}"""))
        assertEquals(PostMessageResult(seq = null, duplicate = false, superseded = true), client.postMessage(envelope()))
        server.enqueue(json(200, """{"seq":7,"duplicate":true}"""))
        assertEquals(PostMessageResult(seq = 7, duplicate = true), client.postMessage(envelope()))
    }

    @Test
    fun `messages and acks use the documented parameters`() = runTest {
        server.enqueue(json(200, """{"messages":[{"seq":3,"envelope":{"v":1}}],"more":true}"""))
        server.enqueue(json(200, "{}"))
        val page = client.messages(limit = 50)
        assertEquals(3L, page.messages.single().seq)
        assertTrue(page.more)
        assertEquals("50", take().url.queryParameter("limit"))
        client.ack(3)
        assertEquals("3", body(take())["up_to"]!!.jsonPrimitive.content)
    }

    @Test
    fun `welcome box is null while waiting`() = runTest {
        server.enqueue(json(404, """{"error":{"code":"not_found","message":"welcome not ready"}}"""))
        assertNull(client.pairWelcomeBox("AAECAwQFBgcICQoLDA0ODw"))
        server.enqueue(json(200, """{"box":{"epk":"e","ct":"c"}}"""))
        assertEquals("e", client.pairWelcomeBox("AAECAwQFBgcICQoLDA0ODw")!!.jsonObject["epk"]!!.jsonPrimitive.content)
    }

    @Test
    fun `pairing routes and revoke use path parameters`() = runTest {
        repeat(3) { server.enqueue(json(200, "{}")) }
        client.pairWelcome("AAECAwQFBgcICQoLDA0ODw", Box("e", "c"))
        assertEquals("/carom/v1/pair/AAECAwQFBgcICQoLDA0ODw/welcome", take().url.encodedPath)
        client.pairReject("AAECAwQFBgcICQoLDA0ODw")
        assertEquals("/carom/v1/pair/AAECAwQFBgcICQoLDA0ODw/reject", take().url.encodedPath)
        client.revoke(HubClient.SELF)
        val revoke = take()
        assertEquals("DELETE", revoke.method)
        assertEquals("/carom/v1/devices/self", revoke.url.encodedPath)
    }

    @Test
    fun `unexpected bodies and proxy errors are typed`() = runTest {
        server.enqueue(json(200, """{"unexpected":true}"""))
        try {
            client.info()
            fail()
        } catch (_: HubException.MalformedResponse) {
        }
        server.enqueue(MockResponse.Builder().code(502).body("<html>bad gateway</html>").build())
        try {
            client.devices()
            fail()
        } catch (e: HubException.Http) {
            assertEquals(HubErrorCode.INTERNAL, e.code)
            assertTrue(e.isRetryable)
        }
    }

    @Test
    fun `network failures are typed`() = runTest {
        server.close()
        try {
            client.info()
            fail()
        } catch (_: HubException.Network) {
        }
    }

    // -------------------------------------------------------------------------

    private fun json(code: Int, body: String) = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()

    private fun take(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS)!!

    private fun body(request: RecordedRequest): JsonObject =
        Json.parseToJsonElement(request.body!!.utf8()).jsonObject

    private fun envelope() = Identity.generate().let { me ->
        Envelopes.seal(
            msgId = UuidV7Generator().next(),
            from = me.deviceId,
            to = listOf(Identity.generate().deviceId),
            collapse = null,
            ttlS = 60,
            plaintext = Payload("x@1", 1, JsonObject(emptyMap())).encode(),
            groupKey = ByteArray(32),
            kv = 1,
            signSk = me.signSk,
        )
    }
}
