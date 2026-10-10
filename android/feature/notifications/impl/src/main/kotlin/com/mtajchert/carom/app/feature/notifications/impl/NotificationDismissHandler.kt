package com.mtajchert.carom.app.feature.notifications.impl

import android.util.Log
import com.mtajchert.carom.app.core.data.sync.IncomingMessageHandler
import com.mtajchert.carom.app.core.protocol.MessageBodies
import com.mtajchert.carom.app.core.protocol.MessageTypes
import com.mtajchert.carom.app.core.protocol.Received
import javax.inject.Inject

/**
 * `notification.dismiss@1` (*Dismiss on phone* on a desktop): cancels the notification if it is
 * still showing. The engine has already applied SPEC §3.3, including rule 7: only a device the
 * posted message went to may reply, and the dismissed key must be that message's key.
 */
class NotificationDismissHandler @Inject constructor(
    private val bridge: ActiveNotificationsBridge,
) : IncomingMessageHandler {
    override val type: String = MessageTypes.NOTIFICATION_DISMISS

    override suspend fun handle(message: Received) {
        val body = MessageBodies.fromJson(MessageBodies.NotificationDismiss.serializer(), message.payload.body) ?: return
        val dismissed = bridge.dismiss(body.key)
        if (!dismissed) Log.i(TAG, "dismiss_skipped ${message.envelope.msgId}")
    }

    private companion object {
        const val TAG = "CaromListener"
    }
}
