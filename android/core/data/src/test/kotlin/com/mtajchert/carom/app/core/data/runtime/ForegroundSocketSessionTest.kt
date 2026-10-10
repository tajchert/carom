package com.mtajchert.carom.app.core.data.runtime

import com.mtajchert.carom.app.core.hub.Backoff
import com.mtajchert.carom.app.core.hub.HubErrorCode
import com.mtajchert.carom.app.core.hub.HubException
import com.mtajchert.carom.app.core.hub.HubSocket
import com.mtajchert.carom.app.core.hub.HubSocketEvent
import com.mtajchert.carom.app.core.testing.runtime.FakeHubSocket
import com.mtajchert.carom.app.core.testing.sync.FakeCaromSync
import com.mtajchert.carom.app.core.testing.sync.FakeSyncWorkScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ForegroundSocketSessionTest {
    private val sync = FakeCaromSync()
    private val state = LiveConnectionState()
    private val sockets = mutableListOf<FakeHubSocket>()
    private val sleeps = mutableListOf<Long>()
    private var paired = true

    private fun session() = ForegroundSocketSession(
        sync = sync,
        state = state,
        connect = { if (paired) FakeHubSocket().also { sockets += it } else null },
        backoff = Backoff(random = { 0.0 }),
        sleep = { sleeps += it },
    )

    private fun TestScope.start() = backgroundScope.launch { session().run() }.also { runCurrent() }

    @Test
    fun `frames are processed and acknowledged while connected`() = runTest {
        val job = start()
        val socket = sockets.single()
        socket.send(HubSocketEvent.Open)
        socket.send(HubSocketEvent.Message(7, JsonObject(mapOf("v" to JsonPrimitive(1)))))
        socket.send(HubSocketEvent.DirectoryChanged)
        runCurrent()

        assertTrue(state.connected.value)
        assertEquals(listOf(7L), sync.processed.map { it.seq })
        assertEquals(listOf(7L), socket.acks)
        assertEquals(1, sync.directoryRefreshes)

        job.cancel()
        runCurrent()
        assertTrue(socket.closed)
        assertFalse(state.connected.value)
    }

    @Test
    fun `pair frames are exposed for the pairing screen`() = runTest {
        start()
        val candidate = HubSocketEvent.PairCandidate("pair-id", JsonObject(emptyMap()))
        val received = backgroundScope.launch { assertEquals(candidate, state.pairCandidates.first()) }
        runCurrent()
        sockets.single().send(candidate)
        runCurrent()
        assertTrue(received.isCompleted)
    }

    @Test
    fun `a dropped connection reconnects with backoff`() = runTest {
        val job = start()
        sockets.single().finish(HubSocketEvent.Failure(HubException.Network(IOException("reset"))))
        runCurrent()
        assertEquals(2, sockets.size)
        assertEquals(listOf(500L), sleeps)
        sockets.last().send(HubSocketEvent.Open)
        sockets.last().finish(HubSocketEvent.Closed(1006, null))
        runCurrent()
        // Open reset the backoff, so the next wait starts over.
        assertEquals(listOf(500L, 500L), sleeps)
        job.cancel()
    }

    @Test
    fun `the revoked frame wipes once and stops`() = runTest {
        val job = start()
        val socket = sockets.single()
        socket.send(HubSocketEvent.Revoked)
        socket.finish(HubSocketEvent.Closed(HubSocket.REVOKED_CLOSE_CODE, null))
        runCurrent()
        assertEquals(1, sync.wipes)
        assertTrue(job.isCompleted)
        assertEquals(1, sockets.size)
    }

    @Test
    fun `a 401 on connect lets the engine latch and stops`() = runTest {
        // The fake engine does not latch, so mimic it: the fetch sees 401 and reports REJECTED.
        val job = start()
        sync.status.value = com.mtajchert.carom.app.core.model.ConnectionStatus.REJECTED
        sockets.single().finish(HubSocketEvent.Failure(HubException.Http(401, HubErrorCode.UNAUTHORIZED, null, null)))
        runCurrent()
        assertEquals(1, sync.fetches)
        assertTrue(job.isCompleted)
    }

    @Test
    fun `nothing connects when the device is not paired`() = runTest {
        paired = false
        val job = start()
        assertTrue(job.isCompleted)
        assertTrue(sockets.isEmpty())
    }

    @Test
    fun `piggyback fetches only when no socket is live`() {
        val scheduler = FakeSyncWorkScheduler()
        val piggyback = PiggybackFetch(scheduler, state)
        piggyback.onSent()
        state.setConnected(true)
        piggyback.onSent()
        assertEquals(listOf(false), scheduler.fetches)
    }
}
