package com.mtajchert.carom.app.core.hub

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class OkHttpHubSocketTest {
    private val server = MockWebServer()
    private val received = LinkedBlockingQueue<String>()
    private val factory = OkHttpHubClientFactory(OkHttpClient(), ClockSkewTracker())

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    @Test
    fun `frames become typed events and acks reach the hub`() = runTest {
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(
                object : ServerListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"t":"msg","seq":5,"envelope":{"v":1}}""")
                        webSocket.send("pong")
                        webSocket.send("""{"t":"dir"}""")
                        webSocket.send("""{"t":"pair","pair_id":"p","candidate":{"device_id":"dev_x"}}""")
                        webSocket.send("""{"t":"future-frame"}""")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        received += text
                        webSocket.send("""{"t":"revoked"}""")
                    }
                },
            ).build(),
        )

        val socket = factory.socket(server.url("/").toString(), "ct1.token")
        socket.events.test(timeout = 5.seconds) {
            assertEquals(HubSocketEvent.Open, awaitItem())
            val msg = awaitItem() as HubSocketEvent.Message
            assertEquals(5L, msg.seq)
            assertEquals(HubSocketEvent.DirectoryChanged, awaitItem())
            assertEquals("p", (awaitItem() as HubSocketEvent.PairCandidate).pairId)

            assertTrue(socket.ack(5))
            assertEquals(HubSocketEvent.Revoked, awaitItem())
            // A revoked device closes the socket itself, without waiting for the Hub.
            assertTrue(awaitItem() is HubSocketEvent.Closed)
            awaitComplete()
        }
        val ack = kotlinx.serialization.json.Json.parseToJsonElement(received.poll(5, TimeUnit.SECONDS)!!).jsonObject
        assertEquals("ack", ack["t"]!!.jsonPrimitive.content)
        assertEquals("5", ack["up_to"]!!.jsonPrimitive.content)

        val upgrade = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/v1/ws", upgrade.url.encodedPath)
        assertEquals("Bearer ct1.token", upgrade.headers["Authorization"])
    }

    @Test
    fun `server close with 4001 completes the events`() = runTest {
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(
                object : ServerListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.close(HubSocket.REVOKED_CLOSE_CODE, "revoked")
                    }
                },
            ).build(),
        )
        val socket = factory.socket(server.url("/").toString(), "t")
        socket.events.test(timeout = 5.seconds) {
            assertEquals(HubSocketEvent.Open, awaitItem())
            assertEquals(HubSocketEvent.Closed(HubSocket.REVOKED_CLOSE_CODE, "revoked"), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `a refused upgrade is a typed failure`() = runTest {
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":{"code":"unauthorized","message":"x"}}""").build())
        val socket = factory.socket(server.url("/").toString(), "t")
        socket.events.test(timeout = 5.seconds) {
            val failure = awaitItem() as HubSocketEvent.Failure
            assertEquals(HubErrorCode.UNAUTHORIZED, (failure.error as HubException.Http).code)
            awaitComplete()
        }
    }

    /** Like a real server, answers the client's close frame so MockWebServer can shut down. */
    private open class ServerListener : WebSocketListener() {
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    @Test
    fun `unknown frames and pong are ignored`() {
        assertNull(OkHttpHubSocket.parseFrame("pong"))
        assertNull(OkHttpHubSocket.parseFrame("""{"t":"msg","seq":"1","envelope":{}}"""))
        assertNull(OkHttpHubSocket.parseFrame("""{"t":"later"}"""))
    }

    @Test
    fun `backoff doubles from one second to a sixty second cap`() {
        val max = Backoff(random = { 1.0 })
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), List(8) { max.next() })
        val min = Backoff(random = { 0.0 })
        assertEquals(500L, min.next())
        min.next()
        min.reset()
        assertEquals(500L, min.next())
    }
}
