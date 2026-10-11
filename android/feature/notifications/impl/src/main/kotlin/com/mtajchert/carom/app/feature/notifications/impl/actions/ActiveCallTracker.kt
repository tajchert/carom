package com.mtajchert.carom.app.feature.notifications.impl.actions

import android.app.Notification
import android.service.notification.StatusBarNotification
import com.mtajchert.carom.app.core.data.runtime.ActionWindowHold
import javax.inject.Inject

/**
 * Keeps the action window open while a call is ringing or in progress, or a media player shows an
 * ongoing notification (spec 5.3). Called from the listener's callbacks on the main thread, so it
 * only reads a few extras and sends a message to the window.
 */
class ActiveCallTracker @Inject constructor(
    private val hold: ActionWindowHold,
) {
    fun onPosted(sbn: StatusBarNotification) {
        val n = sbn.notification
        when {
            isCall(n) -> hold.hold(callSource(sbn.key))
            isPlayingMedia(n) -> hold.hold(mediaSource(sbn.key))
            else -> {
                // The same key may have been a call or a playing player a moment ago.
                hold.release(callSource(sbn.key))
                hold.release(mediaSource(sbn.key))
            }
        }
    }

    fun onRemoved(key: String) {
        hold.release(callSource(key))
        hold.release(mediaSource(key))
    }

    companion object {
        private const val EXTRA_COMPAT_TEMPLATE = "androidx.core.app.extra.COMPAT_TEMPLATE"

        internal fun keepsWindowOpen(n: Notification): Boolean = isCall(n) || isPlayingMedia(n)

        private fun callSource(key: String) = "call:$key"
        private fun mediaSource(key: String) = "media-notification:$key"

        private fun isCall(n: Notification): Boolean = n.category == Notification.CATEGORY_CALL || template(n).endsWith("CallStyle")

        /** A paused player's notification is dismissible; a playing one is ongoing. */
        private fun isPlayingMedia(n: Notification): Boolean =
            template(n).endsWith("MediaStyle") && n.flags and Notification.FLAG_ONGOING_EVENT != 0

        private fun template(n: Notification): String {
            val extras = n.extras ?: return ""
            return extras.getString(Notification.EXTRA_TEMPLATE) ?: extras.getString(EXTRA_COMPAT_TEMPLATE) ?: ""
        }
    }
}
