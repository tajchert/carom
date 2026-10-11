package com.mtajchert.carom.app.feature.notifications.impl.actions

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mtajchert.carom.app.core.data.runtime.ActivitySignal
import com.mtajchert.carom.app.core.model.ActionsFromMac
import com.mtajchert.carom.app.core.model.UserPreferences
import com.mtajchert.carom.app.core.protocol.Envelope
import com.mtajchert.carom.app.core.protocol.MessageBodies
import com.mtajchert.carom.app.core.protocol.MessageTypes
import com.mtajchert.carom.app.core.protocol.Payload
import com.mtajchert.carom.app.core.protocol.Received
import com.mtajchert.carom.app.core.testing.repository.FakeUserPreferencesRepository
import com.mtajchert.carom.app.core.testing.sync.FakeCaromSync
import com.mtajchert.carom.app.feature.notifications.impl.ActiveNotificationsBridge
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class) // android.util.Log, PendingIntent
class NotificationActionHandlerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = FakeUserPreferencesRepository(UserPreferences())
    private val sync = FakeCaromSync()
    private val sender = RecordingSender()
    private val bridge = ActiveNotificationsBridge()
    private val activity = CountingSignal()
    private val live = mutableMapOf<String, StatusBarNotification>()
    private val handler = NotificationActionHandler(
        executor = ActionExecutor(bridge, sender),
        sync = dagger.Lazy { sync },
        preferences = prefs,
        activity = dagger.Lazy { activity },
    )

    private class CountingSignal : ActivitySignal {
        var activities = 0
        override fun onForwarded(online: Set<String>) = Unit
        override fun onActivity() {
            activities++
        }
    }

    init {
        bridge.attach(
            object : ActiveNotificationsBridge.Listener {
                override fun isActive(key: String) = key in live
                override fun cancel(key: String) {
                    live -= key
                }

                override fun find(key: String) = live[key]
            },
        )
    }

    private fun post(key: String) {
        val open = PendingIntent.getBroadcast(context, 0, Intent("test.open").setPackage(context.packageName), PendingIntent.FLAG_MUTABLE)
        val n = Notification.Builder(context, "channel").setSmallIcon(android.R.drawable.ic_dialog_info)
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(context, android.R.drawable.ic_dialog_info), "Open", open).build())
            .build()
        @Suppress("DEPRECATION")
        live[key] = StatusBarNotification("com.chat", "com.chat", 1, null, 10_000, 0, 0, n, Process.myUserHandle(), 1)
    }

    private fun resultOf(index: Int = 0) =
        MessageBodies.fromJson(MessageBodies.NotificationActionResult.serializer(), sync.sent[index].body)

    @Test
    fun `handles notification action`() {
        assertEquals(MessageTypes.NOTIFICATION_ACTION, handler.type)
    }

    @Test
    fun `fires the action and replies ok to the sender of the request`() = runTest {
        post("k1")
        handler.handle(action("k1", MessageBodies.ActionRef.Button(0, "Open", null)))

        assertEquals(1, sender.sent.size)
        val reply = sync.sent.single()
        assertEquals(MessageTypes.NOTIFICATION_ACTION_RESULT, reply.type)
        assertEquals(MSG_ID, reply.replyTo)
        assertEquals("dev_mac", reply.replyToSender)
        assertEquals(MessageBodies.NotificationActionResult(ok = true), resultOf())
        assertEquals(1, activity.activities)
    }

    @Test
    fun `a vanished or changed notification is reported and fires nothing`() = runTest {
        handler.handle(action("k-missing", MessageBodies.ActionRef.Button(0, "Open", null)))
        post("k1")
        handler.handle(action("k1", MessageBodies.ActionRef.Button(0, "Reply", "hi")))

        assertTrue(sender.sent.isEmpty())
        assertEquals(MessageBodies.NotificationActionResult(ok = false, error = "gone"), resultOf(0))
        assertEquals(MessageBodies.NotificationActionResult(ok = false, error = "changed"), resultOf(1))
    }

    @Test
    fun `Off refuses without firing anything`() = runTest {
        post("k1")
        prefs.setActionsFromMac(ActionsFromMac.OFF)

        handler.handle(action("k1", MessageBodies.ActionRef.Button(0, "Open", null)))

        assertTrue(sender.sent.isEmpty())
        assertEquals(MessageBodies.NotificationActionResult(ok = false, error = "failed"), resultOf())
    }

    @Test
    fun `a body that does not parse is ignored without a reply`() = runTest {
        handler.handle(received(kotlinx.serialization.json.JsonObject(emptyMap())))
        assertTrue(sync.sent.isEmpty())
    }

    @Test
    fun `the removed tap action does not parse and is ignored without a reply`() = runTest {
        post("k1")
        handler.handle(received(kotlinx.serialization.json.Json.parseToJsonElement("""{"key":"k1","action":"tap"}""") as kotlinx.serialization.json.JsonObject))
        assertTrue(sender.sent.isEmpty())
        assertTrue(sync.sent.isEmpty())
        assertEquals(0, activity.activities)
        assertNull(sender.sent.firstOrNull())
    }

    private fun action(key: String, ref: MessageBodies.ActionRef) = received(
        MessageBodies.toJson(MessageBodies.NotificationAction.serializer(), MessageBodies.NotificationAction(key, ref)),
    )

    private fun received(body: kotlinx.serialization.json.JsonObject) = Received(
        envelope = Envelope(
            msgId = MSG_ID,
            from = "dev_mac",
            to = listOf("dev_phone"),
            ttlS = 600,
            kv = 1,
            salt = "",
            ct = "",
            sig = "",
        ),
        payload = Payload(
            type = MessageTypes.NOTIFICATION_ACTION,
            ts = 0,
            body = body,
            replyTo = "01a1268a-e800-7000-8000-000000000000",
        ),
    )

    private companion object {
        const val MSG_ID = "01a1268a-e800-7000-8000-000000000001"
    }
}
