package com.mtajchert.carom.app.feature.notifications.impl.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mtajchert.carom.app.core.data.sync.SendResult
import com.mtajchert.carom.app.core.model.AppForwardingMode
import com.mtajchert.carom.app.core.model.UserPreferences
import com.mtajchert.carom.app.core.protocol.MessageTypes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class MediaBridgeTest {
    private val h = MediaHarness(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun `sends the session state one second after the first change`() = runTest {
        val session = FakeMediaSession().also { h.source.sessions[1] = it }
        val bridge = h.start(this)
        bridge.onPosted(h.sbn(1))
        runCurrent()
        advanceTimeBy(999)
        runCurrent()
        assertTrue(h.sync.sent.isEmpty())
        advanceTimeBy(1)
        runCurrent()

        assertEquals(listOf(MessageTypes.MEDIA_STATE), h.sync.sent.map { it.type })
        val body = h.states().single()
        assertEquals(MediaSessionIds.of("com.music", "main"), body.session)
        assertEquals("com.music", body.app.id)
        assertEquals("Music", body.app.name)
        assertEquals("Song", body.title)
        assertEquals("Band", body.artist)
        assertEquals("LP", body.album)
        assertEquals("playing", body.state)
        assertEquals(1_000L, body.positionMs)
        assertEquals(5_000L, body.positionAt)
        assertEquals(200_000L, body.durationMs)
        assertEquals(listOf("play", "pause", "next", "previous", "seek"), body.controls)
        assertEquals(ART_REF, body.art)
        assertEquals(1, session.callbacks.size)
    }

    @Test
    fun `a burst of changes sends once with the latest state`() = runTest {
        val session = FakeMediaSession().also { h.source.sessions[1] = it }
        val bridge = h.start(this)
        bridge.onPosted(h.sbn(1))
        runCurrent()
        advanceTimeBy(300)
        session.snapshot = session.snapshot.copy(title = "Second", artKey = "second")
        session.change()
        runCurrent()
        advanceTimeBy(300)
        session.snapshot = session.snapshot.copy(state = MediaStates.PAUSED)
        session.change()
        runCurrent()
        advanceTimeBy(400)
        runCurrent()

        val body = h.states().single()
        assertEquals("Second", body.title)
        assertEquals("paused", body.state)

        // The next change opens a new window.
        session.snapshot = session.snapshot.copy(state = MediaStates.PLAYING)
        session.change()
        settle()
        assertEquals(2, h.states().size)
    }

    @Test
    fun `a position-only progress tick sends nothing`() = runTest {
        val session = FakeMediaSession().also { h.source.sessions[1] = it }
        val bridge = h.start(this)
        bridge.onPosted(h.sbn(1))
        settle()
        assertEquals(1, h.sync.sent.size)

        session.snapshot = session.snapshot.copy(positionMs = 91_000, positionAt = 95_000)
        session.change()
        settle()
        assertEquals(1, h.sync.sent.size)
        assertEquals(1, h.signal.activity)
    }

    @Test
    fun `a seek that departs from the extrapolated position is sent`() = runTest {
        val session = FakeMediaSession().also { h.source.sessions[1] = it }
        val bridge = h.start(this)
        bridge.onPosted(h.sbn(1))
        settle()
        assertEquals(1, h.sync.sent.size)

        session.snapshot = session.snapshot.copy(positionMs = 90_000, positionAt = 6_000)
        session.change()
        settle()
        assertEquals(2, h.sync.sent.size)
        assertEquals(90_000L, h.states().last().positionMs)
    }

    @Test
    fun `a repost of the same notification keeps one registration and one message`() = runTest {
        val session = FakeMediaSession().also { h.source.sessions[1] = it }
        val bridge = h.start(this)
        bridge.onPosted(h.sbn(1))
        bridge.onPosted(h.sbn(1))
        bridge.onPosted(h.sbn(1))
        settle()
        assertEquals(1, session.callbacks.size)
        assertEquals(1, h.sync.sent.size)
    }

    @Test
    fun `art is uploaded once per track and retried after a failed upload`() = runTest {
        val session = FakeMediaSession().also { h.source.sessions[1] = it }
        val bridge = h.start(this)
        bridge.onPosted(h.sbn(1))
        settle()
        session.snapshot = session.snapshot.copy(state = MediaStates.PAUSED) // same track
        session.change()
        settle()
        assertEquals(1, h.uploader.uploads.size)
        assertEquals(ART_REF, h.states()[1].art)

        // A new track whose upload fails is sent without art, and uploaded again at the next flush.
        h.uploader.result = null
        session.snapshot = session.snapshot.copy(artKey = "other", title = "Other")
        session.change()
        settle()
        assertEquals(2, h.uploader.uploads.size)
        assertNull(h.states()[2].art)

        h.uploader.result = ART_REF
        session.change()
        settle()
        assertEquals(3, h.uploader.uploads.size)
        assertEquals(ART_REF, h.states()[3].art)
    }

    @Test
    fun `a track without art sends no art and does not retry`() = runTest {
        val session = FakeMediaSession().also { it.art = null; h.source.sessions[1] = it }
        val bridge = h.start(this)
        bridge.onPosted(h.sbn(1))
        settle()
        session.change()
        settle()
        assertEquals(1, session.artLoads)
        assertNull(h.states().last().art)
    }

    @Test
    fun `removal sends stopped at once, drops the pending flush and unregisters`() = runTest {
        val session = FakeMediaSession().also { h.source.sessions[1] = it }
        val bridge = h.start(this)
        bridge.onPosted(h.sbn(1))
        runCurrent()
        advanceTimeBy(500)
        bridge.onRemoved(h.sbn(1))
        runCurrent()

        val stopped = h.states().single()
        assertEquals("stopped", stopped.state)
        assertEquals(emptyList<String>(), stopped.controls)
        assertTrue(session.callbacks.isEmpty())

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(1, h.sync.sent.size)
    }

    @Test
    fun `the same session under a new notification replaces the old one without a stopped`() = runTest {
        val session = FakeMediaSession().also { h.source.sessions[1] = it; h.source.sessions[2] = it }
        val bridge = h.start(this)
        bridge.onPosted(h.sbn(1))
        runCurrent()
        bridge.onPosted(h.sbn(2))
        settle()
        assertEquals(1, session.callbacks.size)
        assertEquals(listOf("playing"), h.states().map { it.state })
    }

    @Test
    fun `apps set to Off or Hide content, a global pause and the locked-only switch send nothing`() = runTest {
        val session = FakeMediaSession().also { h.source.sessions[1] = it }
        val bridge = h.start(this)

        h.apps.setMode("com.music", AppForwardingMode.OFF)
        bridge.onPosted(h.sbn(1))
        settle()
        h.apps.setMode("com.music", AppForwardingMode.HIDE_CONTENT)
        bridge.onPosted(h.sbn(1))
        settle()
        h.apps.setMode("com.music", AppForwardingMode.FORWARD)
        h.prefs.setPreferences(UserPreferences(forwardingPaused = true))
        bridge.onPosted(h.sbn(1))
        settle()
        h.prefs.setPreferences(UserPreferences(forwardOnlyWhenLocked = true))
        h.locked = false
        bridge.onPosted(h.sbn(1))
        settle()

        assertTrue(h.sync.sent.isEmpty())
        assertTrue(session.callbacks.isEmpty())
    }

    @Test
    fun `turning the app off while a session is tracked sends stopped`() = runTest {
        h.source.sessions[1] = FakeMediaSession()
        val bridge = h.start(this)
        bridge.onPosted(h.sbn(1))
        settle()
        h.apps.setMode("com.music", AppForwardingMode.OFF)
        bridge.onPosted(h.sbn(1))
        runCurrent()
        assertEquals(listOf("playing", "stopped"), h.states().map { it.state })
    }

    @Test
    fun `pausing forwarding while a session is tracked sends stopped at the next change`() = runTest {
        val session = FakeMediaSession().also { h.source.sessions[1] = it }
        val bridge = h.start(this)
        bridge.onPosted(h.sbn(1))
        settle()
        h.prefs.setPreferences(UserPreferences(forwardingPaused = true))
        session.snapshot = session.snapshot.copy(title = "Next", artKey = "next")
        session.change()
        runCurrent()
        advanceTimeBy(MediaBridge.DEBOUNCE_MS + 1)
        runCurrent()

        assertEquals(listOf("playing", "stopped"), h.states().map { it.state })
        assertTrue(session.callbacks.isEmpty())
        session.change()
        settle()
        assertEquals(2, h.sync.sent.size)
    }

    @Test
    fun `hiding content or unlocking with the locked-only switch while tracked sends stopped`() = runTest {
        val session = FakeMediaSession().also { h.source.sessions[1] = it }
        val bridge = h.start(this)
        bridge.onPosted(h.sbn(1))
        settle()
        h.apps.setMode("com.music", AppForwardingMode.HIDE_CONTENT)
        session.change()
        settle()
        assertEquals(listOf("playing", "stopped"), h.states().map { it.state })

        h.apps.setMode("com.music", AppForwardingMode.FORWARD)
        bridge.onPosted(h.sbn(1))
        settle()
        h.prefs.setPreferences(UserPreferences(forwardOnlyWhenLocked = true))
        h.locked = false
        session.change()
        settle()
        assertEquals(listOf("playing", "stopped", "playing", "stopped"), h.states().map { it.state })
        assertTrue(session.callbacks.isEmpty())
    }

    @Test
    fun `notifications without a media session and our own are ignored`() = runTest {
        val bridge = h.start(this)
        bridge.onPosted(h.sbn(9)) // no session registered for id 9
        bridge.onPosted(h.sbn(1, pkg = "com.mtajchert.carom.app"))
        settle()
        assertTrue(h.sync.sent.isEmpty())
        assertEquals(0, h.source.opened)
    }

    @Test
    fun `each send is a media change for the action window, and queued sends are flushed later`() = runTest {
        h.source.sessions[1] = FakeMediaSession()
        h.sync.nextSendResult = SendResult.Queued("01a1268a-e800-7000-8000-000000000009")
        val bridge = h.start(this)
        bridge.onPosted(h.sbn(1))
        settle()
        assertEquals(1, h.signal.activity)
        assertEquals(1, h.scheduler.flushes)
        bridge.onRemoved(h.sbn(1))
        runCurrent()
        assertEquals(2, h.signal.activity)
    }

    @Test
    fun `stop releases every session and ignores later events`() = runTest {
        val session = FakeMediaSession().also { h.source.sessions[1] = it }
        val bridge = h.start(this)
        bridge.onPosted(h.sbn(1))
        runCurrent()
        bridge.stop()
        assertTrue(session.callbacks.isEmpty())
        bridge.onPosted(h.sbn(1))
        settle()
        assertTrue(h.sync.sent.isEmpty())
    }
}
