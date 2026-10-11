package com.mtajchert.carom.app.feature.notifications.impl.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mtajchert.carom.app.core.protocol.Envelope
import com.mtajchert.carom.app.core.protocol.MessageBodies
import com.mtajchert.carom.app.core.protocol.MessageTypes
import com.mtajchert.carom.app.core.protocol.Payload
import com.mtajchert.carom.app.core.protocol.Received
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class) // android.util.Log
class MediaCommandHandlerTest {
    private val h = MediaHarness(ApplicationProvider.getApplicationContext<Context>())
    private val session = MediaSessionIds.of("com.music", "main")

    @Test
    fun `handles media command`() {
        assertEquals(MessageTypes.MEDIA_COMMAND, MediaCommandHandler(MediaBridgeStub.unstarted(h)).type)
    }

    @Test
    fun `runs each advertised control through the session`() = runTest {
        val fake = FakeMediaSession().also { h.source.sessions[1] = it }
        val bridge = h.start(this)
        val handler = MediaCommandHandler(bridge)
        bridge.onPosted(h.sbn(1))
        runCurrent()

        handler.handle(command(session, "play"))
        handler.handle(command(session, "pause"))
        handler.handle(command(session, "next"))
        handler.handle(command(session, "previous"))
        handler.handle(command(session, "seek", positionMs = 45_000))

        assertEquals(listOf("play", "pause", "next", "previous", "seek@45000"), fake.performed)
        assertEquals(5, h.signal.activity)
    }

    @Test
    fun `ignores unknown, removed and unadvertised sessions and controls`() = runTest {
        val fake = FakeMediaSession().also {
            it.snapshot = it.snapshot.copy(controls = listOf("play", "pause")) // no seek, no skip
            h.source.sessions[1] = it
        }
        val bridge = h.start(this)
        val handler = MediaCommandHandler(bridge)
        bridge.onPosted(h.sbn(1))
        runCurrent()

        handler.handle(command("someone-else", "play")) // unknown session
        handler.handle(command(session, "next")) // not advertised
        handler.handle(command(session, "seek", positionMs = 1_000)) // not advertised
        handler.handle(command(session, "volume")) // not a command
        handler.handle(received(JsonObject(emptyMap()))) // bad body
        assertTrue(fake.performed.isEmpty())

        bridge.onRemoved(h.sbn(1))
        runCurrent()
        handler.handle(command(session, "play")) // the session is no longer active
        assertTrue(fake.performed.isEmpty())
    }

    @Test
    fun `seek needs a non-negative position`() = runTest {
        val fake = FakeMediaSession().also { h.source.sessions[1] = it }
        val bridge = h.start(this)
        bridge.onPosted(h.sbn(1))
        runCurrent()
        assertEquals(CommandOutcome.BAD_ARGUMENT, bridge.command(session, "seek", null))
        assertEquals(CommandOutcome.BAD_ARGUMENT, bridge.command(session, "seek", -5))
        assertEquals(CommandOutcome.DONE, bridge.command(session, "seek", 0))
        assertEquals(listOf("seek@0"), fake.performed)
    }

    @Test
    fun `isActive follows the tracked sessions`() = runTest {
        h.source.sessions[1] = FakeMediaSession()
        val bridge = h.start(this)
        assertEquals(false, bridge.isActive(session))
        bridge.onPosted(h.sbn(1))
        runCurrent()
        assertEquals(true, bridge.isActive(session))
        bridge.onRemoved(h.sbn(1))
        runCurrent()
        assertEquals(false, bridge.isActive(session))
    }

    private fun command(session: String, command: String, positionMs: Long? = null) = received(
        MessageBodies.toJson(MessageBodies.MediaCommand.serializer(), MessageBodies.MediaCommand(session, command, positionMs)),
    )

    private fun received(body: JsonObject) = Received(
        envelope = Envelope(
            msgId = "01a1268a-e800-7000-8000-000000000001",
            from = "dev_mac",
            to = listOf("dev_phone"),
            ttlS = 600,
            kv = 1,
            salt = "",
            ct = "",
            sig = "",
        ),
        payload = Payload(
            type = MessageTypes.MEDIA_COMMAND,
            ts = 0,
            body = body,
            replyTo = "01a1268a-e800-7000-8000-000000000000",
        ),
    )
}

/** A bridge that was never started, enough to read the handler's type. */
private object MediaBridgeStub {
    fun unstarted(h: MediaHarness) = MediaBridge(
        h.source, h.sync, h.scheduler, h.uploader, h.apps, h.prefs, java.util.Optional.empty(),
    )
}
