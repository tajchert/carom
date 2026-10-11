package com.mtajchert.carom.app.feature.notifications.impl

import com.mtajchert.carom.app.core.data.runtime.ActivitySignal
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
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
    private val signal = RecordingSignal()
    private var locked = false
    private val logged = mutableListOf<String>()

    private fun TestScope.forwarder(
        images: ImageLoader = ImageLoader.None,
        uploader: BlobUploader = BlobUploader.None,
    ): NotificationForwarder {
        val env = object : ForwardingEnvironment {
            override val ownPackage = "com.mtajchert.carom.app"
            override fun isScreenLocked() = locked
            override fun now() = testScheduler.currentTime
            override fun log(event: String, detail: String?) {
                logged += if (detail == null) event else "$event $detail"
            }
        }
        return NotificationForwarder(
            scope = backgroundScope,
            sync = sync,
            scheduler = scheduler,
            apps = apps,
            preferences = prefs,
            forwarded = forwardedKeys,
            env = env,
            images = images,
            uploader = uploader,
            activity = signal,
        )
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

    /** A snapshot that carries one image, so the upload path runs. */
    private fun withImage(text: String, key: String = "k1") = snapshot(key = key, text = text).copy(
        imageSources = mapOf(ImageSlot.LargeIcon to ImageSource.FromApp("com.chat", null)),
    )

    private val encodeAll = ImageLoader { sources -> sources.mapValues { EncodedImage(byteArrayOf(1, 2, 3), 8, 8) } }

    /** A schema-valid ref (32-byte id and key, 16-byte hash as base64url) whose id starts with [name]. */
    private fun blobRef(name: String) = MessageBodies.BlobRef(
        id = name.padEnd(43, 'A'),
        key = "k".padEnd(43, 'A'),
        mime = "image/webp",
        size = 3,
        hash = "h".padEnd(22, 'A'),
        w = 8,
        h = 8,
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
        assertEquals("standard", body.template)
        assertEquals(AppForwardingMode.FORWARD, apps.settings["com.chat"]!!.mode)
        assertEquals(1, sync.fetches) // piggyback fetch after a successful send
    }

    @Test
    fun `structurally filtered notifications are neither sent nor recorded`() = runTest {
        val f = forwarder()
        f.onPosted { snapshot(pkg = "com.mtajchert.carom.app") }
        f.onPosted { snapshot().copy(importance = 1) }
        f.onPosted { snapshot().copy(localOnly = true) }
        f.onPosted { snapshot().copy(groupSummary = true, autogroupSummary = true) }
        f.onPosted { null } // unreadable
        runCurrent()
        assertTrue(fake.sent.isEmpty())
        assertTrue(apps.settings.isEmpty())
    }

    @Test
    fun `silent channels and app group summaries are forwarded`() = runTest {
        val f = forwarder()
        f.onPosted { snapshot(key = "silent").copy(importance = 2) }
        f.onPosted { snapshot(key = "summary").copy(groupSummary = true) }
        runCurrent()
        assertEquals(2, fake.sent.size)
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
    fun `ongoing notifications pass for Live Updates, progress and calls`() = runTest {
        val f = forwarder()
        f.onPosted { snapshot(key = "live").copy(ongoing = true, promoted = true) }
        f.onPosted { snapshot(key = "dl").copy(ongoing = true, rich = RichContent(progress = SnapProgress(1, 10, false))) }
        f.onPosted {
            snapshot(key = "call").copy(
                ongoing = true,
                rich = RichContent(template = RichContent.TEMPLATE_CALL, call = SnapCall(type = "incoming")),
            )
        }
        f.onPosted { snapshot(key = "fgs").copy(ongoing = true, foregroundService = true) }
        runCurrent()
        assertEquals(setOf("live", "dl", "call"), fake.sent.map { it.body["key"]!!.jsonPrimitive.content }.toSet())
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
        assertEquals(null, body.text)
        assertEquals(null, body.title)
        assertEquals(true, body.redacted)
        assertEquals(AppForwardingMode.OFF, apps.settings["com.android.systemui"]!!.mode) // deny list default
    }

    @Test
    fun `hide content loads and uploads only the app icon and sends no content`() = runTest {
        val loaded = mutableListOf<Set<ImageSlot>>()
        val uploaded = mutableListOf<Set<ImageSlot>>()
        val f = forwarder(
            images = ImageLoader { sources ->
                loaded += sources.keys
                sources.mapValues { EncodedImage(byteArrayOf(1), 1, 1) }
            },
            uploader = BlobUploader { images ->
                uploaded += images.keys
                images.mapValues { blobRef("id-${it.key}") }
            },
        )
        apps.setMode("com.hide", AppForwardingMode.HIDE_CONTENT)
        val source = ImageSource.FromApp("com.hide", null)
        f.onPosted {
            snapshot(pkg = "com.hide", text = "secret").copy(
                rich = RichContent(actions = listOf(MessageBodies.Action(index = 0, title = "Reply"))),
                imageSources = mapOf(ImageSlot.AppIcon to source, ImageSlot.LargeIcon to source, ImageSlot.Picture to source),
            )
        }
        runCurrent()
        assertEquals(listOf(setOf<ImageSlot>(ImageSlot.AppIcon)), loaded)
        assertEquals(listOf(setOf<ImageSlot>(ImageSlot.AppIcon)), uploaded)
        val body = fake.sent.single().body
        assertEquals(setOf("key", "app", "posted_at", "redacted"), body.keys)
        assertEquals(blobRef("id-AppIcon").id, body["app"]!!.jsonObject["icon"]!!.jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `uploaded images reach the body`() = runTest {
        val f = forwarder(
            images = encodeAll,
            uploader = BlobUploader { mapOf(ImageSlot.LargeIcon to blobRef("large-id")) },
        )
        f.onPosted { withImage("hi") }
        runCurrent()
        assertEquals(blobRef("large-id").id, fake.sent.single().body["large_icon"]!!.jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a body that fails validation falls back to the V1 fields and logs only a code`() = runTest {
        val f = forwarder(
            images = encodeAll,
            uploader = BlobUploader { mapOf(ImageSlot.AppIcon to blobRef("app"), ImageSlot.LargeIcon to blobRef("large")) },
        )
        f.onPosted {
            snapshot(text = "secret text").copy(
                subText = "Work",
                category = "msg",
                rich = RichContent(
                    template = RichContent.TEMPLATE_PROGRESS,
                    progress = SnapProgress(value = 1, max = 10, indeterminate = false, points = listOf(MessageBodies.Point(position = -5))),
                    actions = listOf(MessageBodies.Action(index = 0, title = "Reply")),
                ),
                imageSources = mapOf(
                    ImageSlot.AppIcon to ImageSource.FromApp("com.chat", null),
                    ImageSlot.LargeIcon to ImageSource.FromApp("com.chat", null),
                ),
            )
        }
        runCurrent()
        val body = fake.sent.single().body
        assertTrue(MessageTypes.isValidBody(MessageTypes.NOTIFICATION_POSTED, body))
        assertEquals(setOf("key", "app", "title", "text", "sub_text", "category", "posted_at", "redacted"), body.keys)
        assertEquals(setOf("id", "name"), body["app"]!!.jsonObject.keys)
        assertEquals("secret text", body["text"]!!.jsonPrimitive.content)
        assertEquals(listOf("notification_invalid_body"), logged)
        assertTrue(logged.none { "secret" in it })
    }

    @Test
    fun `a valid rich body passes the pre-send check unchanged`() = runTest {
        val f = forwarder()
        f.onPosted {
            snapshot().copy(
                rich = RichContent(
                    template = RichContent.TEMPLATE_PROGRESS,
                    progress = SnapProgress(value = 1, max = 10, indeterminate = false, points = listOf(MessageBodies.Point(position = 5))),
                ),
            )
        }
        runCurrent()
        val body = fake.sent.single().body
        assertTrue("progress" in body)
        assertEquals(emptyList<String>(), logged)
    }

    @Test
    fun `a failed upload still sends the notification without the image`() = runTest {
        val f = forwarder(images = encodeAll, uploader = BlobUploader { emptyMap() })
        f.onPosted { withImage("hi") }
        runCurrent()
        assertEquals(1, fake.sent.size)
        assertTrue("large_icon" !in fake.sent.single().body)
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
    fun `an unchanged update is dropped, a changed one is sent`() = runTest {
        val f = forwarder()
        f.onPosted { snapshot(text = "1") }
        runCurrent()
        advanceTimeBy(2_000)
        f.onPosted { snapshot(text = "1").copy(postedAt = 99) } // the system re-posted the same content
        runCurrent()
        assertEquals(1, fake.sent.size)

        f.onPosted { snapshot(text = "2") }
        runCurrent()
        assertEquals(listOf("1", "2"), fake.sent.indices.map { postedText(it) })
    }

    @Test
    fun `a dropped send does not make an identical update look like a duplicate`() = runTest {
        val f = forwarder()
        fake.nextSendResult = SendResult.NoRecipients
        f.onPosted { snapshot(text = "1") }
        runCurrent()
        advanceTimeBy(2_000)
        fake.nextSendResult = SendResult.Sent("01a1268a-e800-7000-8000-000000000000")
        f.onPosted { snapshot(text = "1") }
        runCurrent()
        assertEquals(2, fake.sent.size)
    }

    private fun progressing(value: Int) = snapshot(text = "download").copy(
        rich = RichContent(template = RichContent.TEMPLATE_PROGRESS, progress = SnapProgress(value, 100, false)),
    )

    @Test
    fun `progress completing is sent at once`() = runTest {
        val f = forwarder()
        f.onPosted { progressing(50) }
        runCurrent()
        advanceTimeBy(100)
        f.onPosted { progressing(60) }
        runCurrent()
        assertEquals(1, fake.sent.size) // throttled

        advanceTimeBy(100)
        f.onPosted { progressing(100) }
        runCurrent()
        assertEquals(2, fake.sent.size)
        assertEquals(100, fake.sent[1].body["progress"]!!.jsonObject["value"]!!.jsonPrimitive.int)
    }

    @Test
    fun `a call type change is sent at once`() = runTest {
        fun call(type: String) = snapshot(text = "Anna").copy(
            rich = RichContent(template = RichContent.TEMPLATE_CALL, call = SnapCall(type = type)),
        )
        val f = forwarder()
        f.onPosted { call("incoming") }
        runCurrent()
        advanceTimeBy(100)
        f.onPosted { call("ongoing") }
        runCurrent()
        assertEquals(2, fake.sent.size)
        assertEquals("ongoing", fake.sent[1].body["call"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a burst of updates while images upload never lets an older state overtake a newer one`() = runTest {
        val uploader = SlowUploader(delayMs = 5_000)
        val f = forwarder(images = encodeAll, uploader = uploader)
        f.onPosted { withImage("1") }
        runCurrent()
        for (i in 2..4) {
            advanceTimeBy(1_000)
            f.onPosted { withImage("$i") }
            runCurrent()
        }
        advanceTimeBy(30_000)
        runCurrent()

        assertEquals("only the first and the newest state were sent, in order", listOf("1", "4"), fake.sent.indices.map { postedText(it) })
        assertEquals("never two uploads or sends for one key at once", 1, uploader.maxActive)
        assertEquals(2, uploader.calls)
        assertEquals(setOf("k1"), forwardedKeys.keys)
    }

    @Test
    fun `updates of different keys upload at the same time`() = runTest {
        val uploader = SlowUploader(delayMs = 5_000)
        val f = forwarder(images = encodeAll, uploader = uploader)
        f.onPosted { withImage("a", key = "ka") }
        f.onPosted { withImage("b", key = "kb") }
        runCurrent()
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(2, fake.sent.size)
        assertEquals(2, uploader.maxActive)
    }

    @Test
    fun `a removal waits for the send of the same key that is in flight`() = runTest {
        val f = forwarder(images = encodeAll, uploader = SlowUploader(delayMs = 5_000))
        f.onPosted { withImage("1") }
        runCurrent()
        f.onRemoved("k1") // the user dismissed it while its image was uploading
        runCurrent()
        assertTrue(fake.sent.isEmpty())

        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(listOf(MessageTypes.NOTIFICATION_POSTED, MessageTypes.NOTIFICATION_REMOVED), sentTypes)
        assertTrue(forwardedKeys.keys.isEmpty())
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
        assertTrue(signal.online.isEmpty())
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
        assertTrue("a queued send has no online list yet", signal.online.isEmpty())
    }

    @Test
    fun `the action window is told which devices were online when a notification was accepted`() = runTest {
        val f = forwarder()
        fake.nextSendResult = SendResult.Sent("01a1268a-e800-7000-8000-000000000000", setOf("mac-1"))
        f.onPosted { snapshot() }
        runCurrent()
        assertEquals(listOf(setOf("mac-1")), signal.online)
    }

    @Test
    fun `too large is retried once with a smaller budget`() = runTest {
        val cjk = "漢".repeat(4000)
        sync.tooLargeFirst = true
        val f = forwarder()
        f.onPosted {
            snapshot().copy(title = cjk, text = cjk, subText = cjk, rich = RichContent(bigText = cjk, summaryText = cjk))
        }
        runCurrent()
        assertEquals(1, sync.tooLargeBodies.size)
        assertEquals(1, fake.sent.size)
        assertTrue(fake.sent.single().body.toString().toByteArray().size <= NotificationMapper.FALLBACK_BODY_BYTES)
        assertTrue(sync.tooLargeBodies.single().toString().toByteArray().size > NotificationMapper.FALLBACK_BODY_BYTES)
        assertEquals(emptyList<String>(), logged)
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

    @Test
    fun `a send that throws a checked exception does not wedge the key`() = runTest {
        val f = forwarder()
        sync.failNextSend = java.io.IOException("hub down")
        f.onPosted { snapshot(text = "1") }
        runCurrent()
        assertEquals(0, fake.sent.size)
        assertEquals(listOf("notification_failed IOException"), logged)

        advanceTimeBy(2_000)
        f.onPosted { snapshot(text = "2") }
        runCurrent()
        assertEquals(listOf("2"), fake.sent.indices.map { postedText(it) })
    }

    private class RecordingSignal : ActivitySignal {
        val online = mutableListOf<Set<String>>()
        var activity = 0

        override fun onForwarded(online: Set<String>) {
            this.online += online
        }

        override fun onActivity() {
            activity++
        }
    }

    /** Uploads that take [delayMs] and remember how many ran at once. */
    private class SlowUploader(private val delayMs: Long) : BlobUploader {
        var calls = 0
        var maxActive = 0
        private var active = 0

        override suspend fun upload(images: Map<ImageSlot, EncodedImage>): Map<ImageSlot, MessageBodies.BlobRef> {
            calls++
            active++
            maxActive = maxOf(maxActive, active)
            try {
                delay(delayMs)
            } finally {
                active--
            }
            return emptyMap()
        }
    }

    private class CountingSync(private val inner: FakeCaromSync) : CaromSync by inner {
        var fetches = 0
        var tooLargeFirst = false
        var failNextSend: Exception? = null
        val tooLargeBodies = mutableListOf<JsonObject>()

        override suspend fun fetchMessages(): FetchResult {
            fetches++
            return inner.fetchMessages()
        }

        override suspend fun send(
            type: String,
            body: JsonObject,
            replyTo: String?,
            replyToSender: String?,
            onlyTo: Set<String>?,
        ): SendResult {
            failNextSend?.let {
                failNextSend = null
                throw it
            }
            if (tooLargeFirst && type == MessageTypes.NOTIFICATION_POSTED) {
                tooLargeFirst = false
                tooLargeBodies += body
                return SendResult.TooLarge
            }
            return inner.send(type, body, replyTo, replyToSender, onlyTo)
        }
    }
}
