package com.mtajchert.carom.app.feature.notifications.impl

import android.util.Log
import com.mtajchert.carom.app.core.data.sync.IncomingMessageHandler
import com.mtajchert.carom.app.core.protocol.MessageTypes
import com.mtajchert.carom.app.core.protocol.Received
import javax.inject.Inject

/**
 * `notification.resync@1` (a desktop that just launched asks for what is showing): hands the
 * sender to the connected listener, which re-sends the active notifications to that desktop only
 * (SPEC §4.4). Nothing is sent while forwarding is paused or no listener is connected.
 */
class NotificationResyncHandler @Inject constructor(
    private val bridge: ActiveNotificationsBridge,
) : IncomingMessageHandler {
    override val type: String = MessageTypes.NOTIFICATION_RESYNC

    override suspend fun handle(message: Received) {
        if (!bridge.resync(message.envelope.from)) Log.i(TAG, "resync_skipped ${message.envelope.msgId} no_listener")
    }

    private companion object {
        const val TAG = "CaromListener"
    }
}
