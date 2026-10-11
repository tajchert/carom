package com.mtajchert.carom.app.feature.notifications.impl.media

import android.util.Log
import com.mtajchert.carom.app.core.data.sync.IncomingMessageHandler
import com.mtajchert.carom.app.core.protocol.MessageBodies
import com.mtajchert.carom.app.core.protocol.MessageTypes
import com.mtajchert.carom.app.core.protocol.Received
import javax.inject.Inject

/** What happened to a `media.command@1`. Safe to log. */
enum class CommandOutcome { DONE, UNKNOWN_SESSION, UNSUPPORTED, BAD_ARGUMENT }

/**
 * `media.command@1` (the Mac's now-playing controls): runs the command through the session's
 * transport controls. The engine has already applied SPEC §3.3 and spec §3.5: `reply_to` is a
 * `media.state` this phone sent for the same `session`, and the sender was in its `to`. No result
 * message exists; the next `media.state` shows the effect.
 */
class MediaCommandHandler @Inject constructor(
    private val bridge: MediaBridge,
) : IncomingMessageHandler {
    override val type: String = MessageTypes.MEDIA_COMMAND

    override suspend fun handle(message: Received) {
        val body = MessageBodies.fromJson(MessageBodies.MediaCommand.serializer(), message.payload.body) ?: return
        val outcome = bridge.command(body.session, body.command, body.positionMs)
        if (outcome != CommandOutcome.DONE) Log.i(TAG, "media_command_skipped ${message.envelope.msgId} $outcome")
    }

    private companion object {
        const val TAG = "CaromListener"
    }
}
