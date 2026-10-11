package com.mtajchert.carom.app.feature.notifications.impl.actions

import android.app.Notification
import android.service.notification.StatusBarNotification
import com.mtajchert.carom.app.core.data.runtime.ActionWindowHold
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Keeps the action window open while a call is ringing or in progress (spec 5.3). Called from the
 * listener's callbacks on the main thread, so it only reads a few extras and sends a message to
 * the window. Media holds come from [com.mtajchert.carom.app.feature.notifications.impl.media.MediaBridge],
 * which knows whether the session is actually playing (an ongoing media notification may be paused).
 *
 * It remembers which sources it holds so [releaseAll] can drop them when the listener unbinds:
 * no `onNotificationRemoved` arrives then, and a forgotten hold would keep the window open.
 */
class ActiveCallTracker @Inject constructor(
    private val hold: ActionWindowHold,
) {
    private val held: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun onPosted(sbn: StatusBarNotification) {
        // The same key may have been a call a moment ago.
        if (isCall(sbn.notification)) take(callSource(sbn.key)) else drop(callSource(sbn.key))
    }

    fun onRemoved(key: String) {
        drop(callSource(key))
    }

    /** Releases every hold this tracker took; called when the listener disconnects. */
    fun releaseAll() {
        held.toList().forEach(::drop)
    }

    private fun take(source: String) {
        held += source
        hold.hold(source)
    }

    private fun drop(source: String) {
        held -= source
        hold.release(source)
    }

    private companion object {
        const val EXTRA_COMPAT_TEMPLATE = "androidx.core.app.extra.COMPAT_TEMPLATE"

        fun callSource(key: String) = "call:$key"

        fun isCall(n: Notification): Boolean = n.category == Notification.CATEGORY_CALL || template(n).endsWith("CallStyle")

        fun template(n: Notification): String {
            val extras = n.extras ?: return ""
            return extras.getString(Notification.EXTRA_TEMPLATE) ?: extras.getString(EXTRA_COMPAT_TEMPLATE) ?: ""
        }
    }
}
