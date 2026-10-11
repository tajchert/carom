package com.mtajchert.carom.app.feature.notifications.impl.actions

import android.util.Log
import com.mtajchert.carom.app.core.data.repository.UserPreferencesRepository
import com.mtajchert.carom.app.core.data.runtime.ActivitySignal
import com.mtajchert.carom.app.core.data.sync.CaromSync
import com.mtajchert.carom.app.core.data.sync.IncomingMessageHandler
import com.mtajchert.carom.app.core.model.ActionsFromMac
import com.mtajchert.carom.app.core.protocol.MessageBodies
import com.mtajchert.carom.app.core.protocol.MessageTypes
import com.mtajchert.carom.app.core.protocol.Received
import dagger.Lazy
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * `notification.action@1` (a tap, Answer, Decline, a button or a reply from a Mac): runs it with
 * [ActionExecutor] and answers with `notification.action_result@1` to the device that asked.
 *
 * The engine has already applied SPEC §3.3 and the §3.5 rule that the sender was a recipient of
 * the posted message named by `reply_to` and that `key` matches it. Here the only extra gate is
 * the user's setting: while "Actions from Mac" is Off nothing is fired, even if a Mac with an old
 * directory sent one.
 *
 * [sync] and [activity] are lazy: the engine is built from the set of handlers, and the action
 * window depends on the engine.
 */
class NotificationActionHandler @Inject constructor(
    private val executor: ActionExecutor,
    private val sync: Lazy<CaromSync>,
    private val preferences: UserPreferencesRepository,
    private val activity: Lazy<ActivitySignal>,
) : IncomingMessageHandler {
    override val type: String = MessageTypes.NOTIFICATION_ACTION

    override suspend fun handle(message: Received) {
        val body = MessageBodies.fromJson(MessageBodies.NotificationAction.serializer(), message.payload.body) ?: return
        activity.get().onActivity()
        val enabled = preferences.userPreferences.first().actionsFromMac != ActionsFromMac.OFF
        val outcome = if (enabled) executor.execute(body.key, body.action) else ActionOutcome.FAILED
        Log.i(TAG, "action_done ${message.envelope.msgId} ${outcome.name.lowercase()}")
        val result = MessageBodies.NotificationActionResult(ok = outcome == ActionOutcome.OK, error = outcome.error)
        sync.get().send(
            type = MessageTypes.NOTIFICATION_ACTION_RESULT,
            body = MessageBodies.toJson(MessageBodies.NotificationActionResult.serializer(), result),
            replyTo = message.envelope.msgId,
            replyToSender = message.envelope.from,
        )
    }

    private companion object {
        const val TAG = "CaromListener"
    }
}
