package com.mtajchert.carom.app.feature.notifications.impl

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mtajchert.carom.app.core.protocol.Envelope
import com.mtajchert.carom.app.core.protocol.MessageTypes
import com.mtajchert.carom.app.core.protocol.Payload
import com.mtajchert.carom.app.core.protocol.Received
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class) // android.util.Log
class NotificationResyncHandlerTest {
    private val requests = mutableListOf<String>()
    private val listener = object : ActiveNotificationsBridge.Listener {
        override fun isActive(key: String) = false
        override fun cancel(key: String) = Unit
        override fun resync(requester: String) {
            requests += requester
        }
    }
    private val bridge = ActiveNotificationsBridge()
    private val handler = NotificationResyncHandler(bridge)

    @Test
    fun `handles notification resync`() {
        assertEquals(MessageTypes.NOTIFICATION_RESYNC, handler.type)
    }

    @Test
    fun `passes the sender to the connected listener as the requester`() = runTest {
        bridge.attach(listener)
        handler.handle(resync(from = "dev_mac"))
        assertEquals(listOf("dev_mac"), requests)
    }

    @Test
    fun `does nothing without a connected listener`() = runTest {
        handler.handle(resync(from = "dev_mac"))
        bridge.attach(listener)
        bridge.detach(listener)
        handler.handle(resync(from = "dev_mac"))
        assertEquals(emptyList<String>(), requests)
    }

    private fun resync(from: String) = Received(
        envelope = Envelope(
            msgId = "01a1268a-e800-7000-8000-000000000001",
            from = from,
            to = listOf("dev_phone"),
            ttlS = 86_400,
            kv = 1,
            salt = "",
            ct = "",
            sig = "",
        ),
        payload = Payload(type = MessageTypes.NOTIFICATION_RESYNC, ts = 0, body = JsonObject(emptyMap())),
    )
}
