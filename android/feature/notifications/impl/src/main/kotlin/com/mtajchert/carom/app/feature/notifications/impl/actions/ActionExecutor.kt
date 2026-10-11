package com.mtajchert.carom.app.feature.notifications.impl.actions

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.os.BundleCompat
import com.mtajchert.carom.app.core.protocol.MessageBodies.ActionRef
import com.mtajchert.carom.app.feature.notifications.impl.ActiveNotificationsBridge
import javax.inject.Inject

/** How an action ended; [error] is the `notification.action_result@1` code (null for [OK]). */
enum class ActionOutcome(val error: String?) {
    OK(null),
    GONE("gone"),
    CHANGED("changed"),
    BLOCKED("blocked"),
    FAILED("failed"),
}

/**
 * Runs one `notification.action@1` against the live notification (spec §5.3 steps 1-5).
 *
 * The Mac decided from a snapshot that may be old, so nothing here trusts position alone: a
 * button must match both `index` and `title`, otherwise the answer is [ActionOutcome.CHANGED] and
 * no intent is fired. A notification that is no longer showing is [ActionOutcome.GONE].
 */
class ActionExecutor @Inject constructor(
    private val bridge: ActiveNotificationsBridge,
    private val sender: PendingIntentSender,
) {
    private class Target(val intent: PendingIntent, val fillIn: Intent? = null)

    fun execute(key: String, action: ActionRef): ActionOutcome {
        val notification = bridge.find(key)?.notification ?: return ActionOutcome.GONE
        val target = try {
            resolve(notification, action)
        } catch (_: RuntimeException) {
            return ActionOutcome.FAILED // malformed extras from the app; nothing was fired
        } ?: return ActionOutcome.CHANGED
        return fire(target)
    }

    private fun resolve(n: Notification, action: ActionRef): Target? = when (action) {
        ActionRef.Tap -> n.contentIntent?.let { Target(it) }
        ActionRef.Answer -> callTarget(n, Notification.EXTRA_ANSWER_INTENT, answer = true)
        ActionRef.Decline -> callTarget(n, Notification.EXTRA_DECLINE_INTENT, answer = false)
        is ActionRef.Button -> button(n, action)
    }

    private fun button(n: Notification, ref: ActionRef.Button): Target? {
        val candidate = n.actions?.getOrNull(ref.index) ?: return null
        if (candidate.title?.toString() != ref.title) return null
        val intent = candidate.actionIntent ?: return null
        val text = ref.replyText ?: return Target(intent)
        val input = candidate.remoteInputs?.firstOrNull { it.allowFreeFormInput } ?: return null
        return Target(intent, replyIntent(input, text))
    }

    private fun replyIntent(input: RemoteInput, text: String): Intent {
        val fillIn = Intent()
        RemoteInput.addResultsToIntent(arrayOf(input), fillIn, Bundle().apply { putCharSequence(input.resultKey, text) })
        RemoteInput.setResultsSource(fillIn, RemoteInput.SOURCE_FREE_FORM_INPUT)
        return fillIn
    }

    /** API 31+ keeps the CallStyle intents in the extras; older `NotificationCompat.CallStyle` adds actions. */
    private fun callTarget(n: Notification, extra: String, answer: Boolean): Target? {
        n.extras?.let { BundleCompat.getParcelable(it, extra, PendingIntent::class.java) }?.let { return Target(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return null
        return legacyCallAction(n, answer)?.actionIntent?.let { Target(it) }
    }

    private fun fire(target: Target): ActionOutcome = try {
        sender.send(target.intent, target.fillIn, backgroundStartOptions())
        ActionOutcome.OK
    } catch (_: PendingIntent.CanceledException) {
        ActionOutcome.FAILED
    } catch (_: SecurityException) {
        ActionOutcome.BLOCKED
    } catch (_: RuntimeException) {
        ActionOutcome.FAILED
    }

    companion object {
        /**
         * An incoming-call notification from `NotificationCompat.CallStyle` before API 31 lists the
         * decline button first and the answer button last. Null when it is not an incoming call.
         */
        internal fun legacyCallAction(n: Notification, answer: Boolean): Notification.Action? {
            val incoming = n.extras?.getInt(NotificationCompat.EXTRA_CALL_TYPE) == NotificationCompat.CallStyle.CALL_TYPE_INCOMING
            val actions = n.actions
            if (!incoming || actions == null || actions.size < 2) return null
            return if (answer) actions.last() else actions.first()
        }

        /** Lets the target activity start although Carom is in the background (API 34+); spec §5.3 step 4. */
        internal fun backgroundStartOptions(): Bundle? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
            return ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        }
    }
}
