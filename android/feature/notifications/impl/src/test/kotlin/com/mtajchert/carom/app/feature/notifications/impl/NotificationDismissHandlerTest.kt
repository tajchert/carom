package com.mtajchert.carom.app.feature.notifications.impl

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mtajchert.carom.app.core.protocol.Envelope
import com.mtajchert.carom.app.core.protocol.MessageBodies
import com.mtajchert.carom.app.core.protocol.MessageTypes
import com.mtajchert.carom.app.core.protocol.Payload
import com.mtajchert.carom.app.core.protocol.Received
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class) // android.util.Log
class NotificationDismissHandlerTest {
    private val active = mutableSetOf("0|com.chat|1|null|10")
    private val cancelled = mutableListOf<String>()
    private val listener = object : ActiveNotificationsBridge.Listener {
        override fun isActive(key: String) = key in active
        override fun cancel(key: String) {
            cancelled += key
            active -= key
        }
    }
    private val bridge = ActiveNotificationsBridge()
    private val handler = NotificationDismissHandler(bridge)

    @Test
    fun `handles notification dismiss`() {
        assertEquals(MessageTypes.NOTIFICATION_DISMISS, handler.type)
    }

    @Test
    fun `cancels an active notification`() = runTest {
        bridge.attach(listener)
        handler.handle(dismiss("0|com.chat|1|null|10"))
        assertEquals(listOf("0|com.chat|1|null|10"), cancelled)
    }

    @Test
    fun `ignores keys that are gone, bad bodies and a disconnected listener`() = runTest {
        handler.handle(dismiss("0|com.chat|1|null|10")) // not attached
        bridge.attach(listener)
        handler.handle(dismiss("0|com.chat|2|null|10"))
        handler.handle(received(JsonObject(emptyMap())))
        assertTrue(cancelled.isEmpty())

        bridge.detach(listener)
        assertFalse(bridge.dismiss("0|com.chat|1|null|10"))
    }

    @Test
    fun `detaching a stale listener keeps the current one`() {
        val other = object : ActiveNotificationsBridge.Listener {
            override fun isActive(key: String) = false
            override fun cancel(key: String) = Unit
        }
        bridge.attach(listener)
        bridge.detach(other)
        assertTrue(bridge.dismiss("0|com.chat|1|null|10"))
    }

    private fun dismiss(key: String) =
        received(MessageBodies.toJson(MessageBodies.NotificationDismiss.serializer(), MessageBodies.NotificationDismiss(key)))

    private fun received(body: JsonObject) = Received(
        envelope = Envelope(
            msgId = "01a1268a-e800-7000-8000-000000000001",
            from = "dev_mac",
            to = listOf("dev_phone"),
            ttlS = 86_400,
            kv = 1,
            salt = "",
            ct = "",
            sig = "",
        ),
        payload = Payload(
            type = MessageTypes.NOTIFICATION_DISMISS,
            ts = 0,
            body = body,
            replyTo = "01a1268a-e800-7000-8000-000000000000",
        ),
    )
}
