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
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
    private val localNow = 1_791_758_047_000L // Sun, 11 Oct 2026 22:34:07 GMT
    private val clockSkew = ClockSkewTracker { localNow }
    private lateinit var client: HubClient

    @Before
    fun setUp() {
        server.start()
        // A Hub mounted under a path prefix must keep that prefix.
        client = OkHttpHubClientFactory(http, clockSkew).client(server.url("/carom/").toString()) { token }
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `info needs no auth and parses every field`() = runTest {
        server.enqueue(json(200, """{"name":"carom-hub","protocol":1,"version":"0.1.0","setup_done":true,"jurisdiction":null,"extra":1}"""))
        val info = client.info()
        assertEquals(HubInfo("carom-hub", 1, "0.1.0", true, null), info)
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
        server.enqueue(json(200, """{"devices":[{"id":"dev_a","sign_pub":"s","kx_pub":"k","created_at":1,"last_seen":2,"record":{"id":"dev_a"}}]}"""))
        val devices = client.devices()
        assertEquals(1, devices.size)
        assertEquals(2L, devices[0].lastSeen)
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
    fun `the Date header of every response feeds the clock check`() = runTest {
        server.enqueue(json(200, """{"name":"carom-hub","protocol":1,"version":"0.1.0","setup_done":true,"jurisdiction":null}""")
            .newBuilder().addHeader("Date", "Sun, 11 Oct 2026 22:44:07 GMT").build())
        client.info()
        assertEquals(10L, clockSkew.drift.value?.minutes)
        assertFalse(clockSkew.drift.value!!.localAhead)
        // An error response counts too, and a correct Date clears the drift.
        server.enqueue(json(404, """{"error":{"code":"not_found"}}""")
            .newBuilder().addHeader("Date", "Sun, 11 Oct 2026 22:34:09 GMT").build())
        try {
            client.devices()
            fail()
        } catch (_: HubException.Http) {
        }
        assertNull(clockSkew.drift.value)
    }

    @Test
    fun `a clock skew 400 exposes hub_time and feeds the clock check`() = runTest {
        val hubTime = localNow - 30 * 60_000L
        server.enqueue(json(400, """{"error":{"code":"bad_request","message":"msg_id time is outside the allowed window; check the device clock"},"clock_skew":true,"hub_time":$hubTime}"""))
        try {
            client.postMessage(envelope())
            fail()
        } catch (e: HubException.Http) {
            assertTrue(e.isClockSkew)
            assertEquals(hubTime, e.hubTime)
            assertFalse(e.isRetryable)
        }
        val drift = clockSkew.drift.value!!
        assertEquals(30L, drift.minutes)
        assertTrue(drift.localAhead)
    }

    @Test
    fun `a plain 400 is not a clock skew`() = runTest {
        server.enqueue(json(400, """{"error":{"code":"bad_request","message":"bad"}}"""))
        try {
            client.postMessage(envelope())
            fail()
        } catch (e: HubException.Http) {
            assertFalse(e.isClockSkew)
            assertNull(e.hubTime)
        }
        assertNull(clockSkew.drift.value)
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

    @Test
    fun `post message result carries the online recipients`() = runTest {
        server.enqueue(json(200, """{"seq":4,"duplicate":false,"online":["dev_a","dev_b"]}"""))
        assertEquals(PostMessageResult(seq = 4, online = listOf("dev_a", "dev_b")), client.postMessage(envelope()))
        server.enqueue(json(200, """{"seq":5}"""))
        assertEquals(emptyList<String>(), client.postMessage(envelope()).online)
    }

    @Test
    fun `put blob sends raw bytes with ttl and msg id`() = runTest {
        val ct = ByteArray(300) { it.toByte() }
        server.enqueue(json(201, """{"id":"abcDEF-_123","size":300,"expires_at":1791650000000}"""))
        val info = client.putBlob("abcDEF-_123", ct, ttlS = 3600, msgId = "01a1268a-e800-7614-b735-9b848038782d")
        assertEquals(BlobInfo("abcDEF-_123", 300, 1_791_650_000_000), info)
        val request = take()
        assertEquals("PUT", request.method)
        assertEquals("/carom/v1/blobs/abcDEF-_123", request.url.encodedPath)
        assertEquals("3600", request.url.queryParameter("ttl_s"))
        assertEquals("01a1268a-e800-7614-b735-9b848038782d", request.url.queryParameter("msg_id"))
        assertEquals("application/octet-stream", request.headers["Content-Type"])
        assertEquals("Bearer ct1.token", request.headers["Authorization"])
        assertArrayEquals(ct, request.body!!.toByteArray())
    }

    @Test
    fun `put blob without a msg id omits the parameter and an existing blob answers 200`() = runTest {
        server.enqueue(json(200, """{"id":"x","size":1,"expires_at":5}"""))
        assertEquals(BlobInfo("x", 1, 5), client.putBlob("x", byteArrayOf(1), ttlS = 60))
        assertNull(take().url.queryParameter("msg_id"))
    }

    @Test
    fun `put blob errors are typed and quota is not retryable`() = runTest {
        server.enqueue(json(400, """{"error":{"code":"bad_request","message":"hash"}}"""))
        try {
            client.putBlob("x", byteArrayOf(1), ttlS = 60)
            fail()
        } catch (e: HubException.Http) {
            assertEquals(HubErrorCode.BAD_REQUEST, e.code)
        }
        server.enqueue(json(413, """{"error":{"code":"too_large","message":"big"}}"""))
        try {
            client.putBlob("x", byteArrayOf(1), ttlS = 60)
            fail()
        } catch (e: HubException.Http) {
            assertEquals(HubErrorCode.TOO_LARGE, e.code)
        }
        server.enqueue(json(429, """{"error":{"code":"rate_limited","message":"quota"},"quota":true}"""))
        try {
            client.putBlob("x", byteArrayOf(1), ttlS = 60)
            fail()
        } catch (e: HubException.Http) {
            assertTrue(e.isQuotaExceeded)
            assertFalse(e.isRetryable)
        }
        server.enqueue(json(429, """{"error":{"code":"rate_limited","message":"slow"}}"""))
        try {
            client.putBlob("x", byteArrayOf(1), ttlS = 60)
            fail()
        } catch (e: HubException.Http) {
            assertFalse(e.isQuotaExceeded)
            assertTrue(e.isRetryable)
        }
    }

    @Test
    fun `head blob is true on 200 and false on 404 and can touch`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).addHeader("x-expires-at", "123").build())
        assertTrue(client.headBlob("abc"))
        val plain = take()
        assertEquals("HEAD", plain.method)
        assertEquals("/carom/v1/blobs/abc", plain.url.encodedPath)
        assertNull(plain.url.queryParameter("touch"))

        server.enqueue(MockResponse.Builder().code(200).addHeader("x-expires-at", "456").build())
        assertTrue(client.headBlob("abc", touchTtlS = 7200))
        val touch = take()
        assertEquals("1", touch.url.queryParameter("touch"))
        assertEquals("7200", touch.url.queryParameter("ttl_s"))

        server.enqueue(MockResponse.Builder().code(404).build())
        assertFalse(client.headBlob("missing"))
        server.enqueue(MockResponse.Builder().code(403).build())
        try {
            client.headBlob("abc")
            fail()
        } catch (e: HubException.Http) {
            assertEquals(HubErrorCode.FORBIDDEN, e.code)
        }
    }

    @Test
    fun `get blob returns the bytes and null on 404`() = runTest {
        val ct = ByteArray(2048) { (it * 3).toByte() }
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/octet-stream").body(Buffer().write(ct)).build())
        assertArrayEquals(ct, client.getBlob("abc"))
        val request = take()
        assertEquals("GET", request.method)
        assertEquals("/carom/v1/blobs/abc", request.url.encodedPath)
        assertEquals("Bearer ct1.token", request.headers["Authorization"])

        server.enqueue(json(404, """{"error":{"code":"not_found","message":"no blob"}}"""))
        assertNull(client.getBlob("abc"))
    }

    @Test
    fun `blob ids that are not base64url are refused before any request`() = runTest {
        try {
            client.getBlob("../v1/devices")
            fail()
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(0, server.requestCount)
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
