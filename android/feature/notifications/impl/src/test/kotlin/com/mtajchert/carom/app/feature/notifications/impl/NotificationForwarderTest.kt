package com.mtajchert.carom.app.feature.notifications.impl

import com.mtajchert.carom.app.core.data.sync.CaromSync
import com.mtajchert.carom.app.core.data.sync.FetchResult
import com.mtajchert.carom.app.core.data.sync.SendResult
import com.mtajchert.carom.app.core.model.AppForwardingMode
import com.mtajchert.carom.app.core.model.UserPreferences
import com.mtajchert.carom.app.core.protocol.MessageBodies
import com.mtajchert.carom.app.core.protocol.MessageTypes
import com.mtajchert.carom.app.core.testing.repository.FakeUserPreferencesRepository
import com.mtajchert.carom.app.core.testing.sync.FakeCaromSync
import com.mtajchert.carom.app.core.testing.sync.FakeSyncWorkScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationForwarderTest {
    private val fake = FakeCaromSync()
    private val sync = CountingSync(fake)
    private val scheduler = FakeSyncWorkScheduler()
    private val apps = FakeNotificationAppsRepository()
    private val prefs = FakeUserPreferencesRepository()
    private val forwardedKeys = FakeForwardedKeys()
    private var locked = false
    private val logged = mutableListOf<String>()

    private fun TestScope.forwarder(): NotificationForwarder {
        val env = object : ForwardingEnvironment {
            override val ownPackage = "com.mtajchert.carom.app"
            override val hiddenText = "New notification"
            override fun isScreenLocked() = locked
            override fun now() = testScheduler.currentTime
            override fun log(event: String, detail: String?) {
                logged += if (detail == null) event else "$event $detail"
            }
        }
        return NotificationForwarder(backgroundScope, sync, scheduler, apps, prefs, forwardedKeys, env)
    }

    private fun snapshot(key: String = "k1", pkg: String = "com.chat", text: String = "hello") = NotificationSnapshot(
        key = key,
        packageName = pkg,
        appLabel = "Chat",
        postedAt = 1,
        importance = 3,
        title = "Anna",
        text = text,
    )

    private val sentTypes get() = fake.sent.map { it.type }

    private fun postedText(i: Int) = fake.sent[i].body["text"]!!.jsonPrimitive.content

    @Test
    fun `forwards a notification and records the app on first sight`() = runTest {
        val f = forwarder()
        f.onPosted { snapshot() }
        runCurrent()
        assertEquals(emptyList<String>(), logged)
        assertEquals(listOf(MessageTypes.NOTIFICATION_POSTED), sentTypes)
        val body = MessageBodies.fromJson(MessageBodies.NotificationPosted.serializer(), fake.sent.single().body)!!
        assertEquals("Chat", body.app.name)
        assertEquals("hello", body.text)
        assertEquals(AppForwardingMode.FORWARD, apps.settings["com.chat"]!!.mode)
        assertEquals(1, sync.fetches) // piggyback fetch after a successful send
    }

    @Test
    fun `structurally filtered notifications are neither sent nor recorded`() = runTest {
        val f = forwarder()
        f.onPosted { snapshot(pkg = "com.mtajchert.carom.app") }
        f.onPosted { snapshot().copy(importance = 1) }
        f.onPosted { snapshot().copy(localOnly = true) }
        f.onPosted { null } // unreadable
        runCurrent()
        assertTrue(fake.sent.isEmpty())
        assertTrue(apps.settings.isEmpty())
    }

    @Test
    fun `an ongoing notification is recorded but forwarded only when the app setting allows it`() = runTest {
        val f = forwarder()
        f.onPosted { snapshot().copy(ongoing = true) }
        runCurrent()
        assertTrue(fake.sent.isEmpty())
        assertTrue("com.chat" in apps.settings)

        apps.setForwardOngoing("com.chat", true)
        f.onPosted { snapshot(key = "k2").copy(ongoing = true) }
        runCurrent()
        assertEquals(1, fake.sent.size)
    }

    @Test
    fun `pause and only-when-locked stop forwarding but still record the app`() = runTest {
        val f = forwarder()
        prefs.setPreferences(UserPreferences(forwardingPaused = true))
        f.onPosted { snapshot() }
        runCurrent()
        assertTrue(fake.sent.isEmpty())
        assertTrue("com.chat" in apps.settings)

        prefs.setPreferences(UserPreferences(forwardOnlyWhenLocked = true))
        f.onPosted { snapshot(key = "k2") }
        runCurrent()
        assertTrue(fake.sent.isEmpty())

        locked = true
        f.onPosted { snapshot(key = "k3") }
        runCurrent()
        assertEquals(1, fake.sent.size)
    }

    @Test
    fun `per-app modes off and hide content`() = runTest {
        val f = forwarder()
        apps.setMode("com.off", AppForwardingMode.OFF)
        apps.setMode("com.hide", AppForwardingMode.HIDE_CONTENT)
        f.onPosted { snapshot(key = "a", pkg = "com.off") }
        f.onPosted { snapshot(key = "b", pkg = "com.hide", text = "secret") }
        f.onPosted { snapshot(key = "c", pkg = "com.android.systemui") }
        runCurrent()
        assertEquals(1, fake.sent.size)
        val body = MessageBodies.fromJson(MessageBodies.NotificationPosted.serializer(), fake.sent.single().body)!!
        assertEquals("New notification", body.text)
        assertEquals(null, body.title)
        assertEquals(AppForwardingMode.OFF, apps.settings["com.android.systemui"]!!.mode) // deny list default
    }

    @Test
    fun `updates are throttled to one per 1_5 seconds per key and the newest wins`() = runTest {
        val f = forwarder()
        f.onPosted { snapshot(text = "1") }
        runCurrent()
        advanceTimeBy(500)
        f.onPosted { snapshot(text = "2") }
        f.onPosted { snapshot(key = "other", text = "x") }
        runCurrent()
        advanceTimeBy(500)
        f.onPosted { snapshot(text = "3") }
        runCurrent()
        assertEquals(listOf("1", "x"), fake.sent.indices.map { postedText(it) })

        advanceTimeBy(499) // t = 1499
        runCurrent()
        assertEquals(2, fake.sent.size)

        advanceTimeBy(1) // t = 1500
        runCurrent()
        assertEquals(listOf("1", "x", "3"), fake.sent.indices.map { postedText(it) })

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(3, fake.sent.size)
    }

    @Test
    fun `removed is sent only for forwarded keys and cancels a pending update`() = runTest {
        val f = forwarder()
        f.onRemoved("never-forwarded")
        f.onPosted { snapshot(text = "1") }
        runCurrent()
        f.onPosted { snapshot(text = "2") } // throttled
        f.onRemoved("k1")
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf(MessageTypes.NOTIFICATION_POSTED, MessageTypes.NOTIFICATION_REMOVED), sentTypes)
        assertEquals("k1", fake.sent[1].body["key"]!!.jsonPrimitive.content)

        f.onRemoved("k1") // only once
        runCurrent()
        assertEquals(2, fake.sent.size)
    }

    @Test
    fun `a forwarded key survives a listener reconnect and its removal is still sent`() = runTest {
        forwarder().onPosted { snapshot() }
        runCurrent()
        assertEquals(setOf("k1"), forwardedKeys.keys)

        // The listener reconnected (or the process restarted): a new forwarder, same stored keys.
        val next = forwarder()
        next.onRemoved("k1")
        runCurrent()
        assertEquals(listOf(MessageTypes.NOTIFICATION_POSTED, MessageTypes.NOTIFICATION_REMOVED), sentTypes)
        assertTrue(forwardedKeys.keys.isEmpty())
    }

    @Test
    fun `reconcile sends removed for forwarded keys that are no longer showing`() = runTest {
        val f = forwarder()
        f.onPosted { snapshot(key = "gone") }
        f.onPosted { snapshot(key = "still") }
        runCurrent()

        forwarder().reconcile { setOf("still", "other") }
        runCurrent()
        assertEquals(MessageTypes.NOTIFICATION_REMOVED, fake.sent.last().type)
        assertEquals("gone", fake.sent.last().body["key"]!!.jsonPrimitive.content)
        assertEquals(setOf("still"), forwardedKeys.keys)

        // An unreadable list changes nothing.
        forwarder().reconcile { null }
        runCurrent()
        assertEquals(3, fake.sent.size)
    }

    @Test
    fun `a key that had no recipients is not removed later`() = runTest {
        val f = forwarder()
        fake.nextSendResult = SendResult.NoRecipients
        f.onPosted { snapshot() }
        runCurrent()
        f.onRemoved("k1")
        runCurrent()
        assertEquals(listOf(MessageTypes.NOTIFICATION_POSTED), sentTypes)
        assertEquals(0, sync.fetches)
    }

    @Test
    fun `a queued send schedules the outbox worker`() = runTest {
        val f = forwarder()
        fake.nextSendResult = SendResult.Queued("01a1268a-e800-7000-8000-000000000000")
        f.onPosted { snapshot() }
        runCurrent()
        assertEquals(1, scheduler.flushes)
        f.onRemoved("k1")
        runCurrent()
        assertEquals(2, scheduler.flushes)
        assertEquals(MessageTypes.NOTIFICATION_REMOVED, sentTypes.last())
    }

    @Test
    fun `piggyback fetches are coalesced`() = runTest {
        val f = forwarder()
        repeat(5) { i -> f.onPosted { snapshot(key = "k$i") } }
        runCurrent()
        assertEquals(5, fake.sent.size)
        assertEquals(1, sync.fetches)
        advanceTimeBy(NotificationForwarder.FETCH_INTERVAL_MS)
        f.onPosted { snapshot(key = "late") }
        runCurrent()
        assertEquals(2, sync.fetches)
    }

    @Test
    fun `a failing read does not stop the forwarder`() = runTest {
        val f = forwarder()
        f.onPosted { throw IllegalStateException("bad extras") }
        f.onPosted { snapshot() }
        runCurrent()
        assertEquals(1, fake.sent.size)
        assertEquals(listOf("notification_failed IllegalStateException"), logged)
    }

    private class CountingSync(private val inner: FakeCaromSync) : CaromSync by inner {
        var fetches = 0
        override suspend fun fetchMessages(): FetchResult {
            fetches++
            return inner.fetchMessages()
        }
    }
}
