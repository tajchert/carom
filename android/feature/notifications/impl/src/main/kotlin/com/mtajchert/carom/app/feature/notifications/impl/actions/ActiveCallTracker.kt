package com.mtajchert.carom.app.feature.notifications.impl.actions

import android.app.Notification
import android.service.notification.StatusBarNotification
import com.mtajchert.carom.app.core.data.runtime.ActionWindowHold
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Keeps the action window open while a call is ringing or in progress, or a media player shows an
 * ongoing notification (spec 5.3). Called from the listener's callbacks on the main thread, so it
 * only reads a few extras and sends a message to the window.
 *
 * It remembers which sources it holds so [releaseAll] can drop them when the listener unbinds:
 * no `onNotificationRemoved` arrives then, and a forgotten hold would keep the window open.
 */
class ActiveCallTracker @Inject constructor(
    private val hold: ActionWindowHold,
) {
    private val held: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun onPosted(sbn: StatusBarNotification) {
        val n = sbn.notification
        when {
            isCall(n) -> take(callSource(sbn.key))
            isPlayingMedia(n) -> take(mediaSource(sbn.key))
            else -> {
                // The same key may have been a call or a playing player a moment ago.
                drop(callSource(sbn.key))
                drop(mediaSource(sbn.key))
            }
        }
    }

    fun onRemoved(key: String) {
        drop(callSource(key))
        drop(mediaSource(key))
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
        val MEDIA_TEMPLATES = setOf(
            "android.app.Notification\$MediaStyle",
            "android.app.Notification\$DecoratedMediaCustomViewStyle",
        )

        fun callSource(key: String) = "call:$key"
        fun mediaSource(key: String) = "media-notification:$key"

        fun isCall(n: Notification): Boolean = n.category == Notification.CATEGORY_CALL || template(n).endsWith("CallStyle")

        /** A paused player's notification is dismissible; a playing one is ongoing. */
        fun isPlayingMedia(n: Notification): Boolean = isMedia(n) && n.flags and Notification.FLAG_ONGOING_EVENT != 0

        /** The extractor's media rule (framework templates or a media session), plus compat `...MediaStyle` templates. */
        fun isMedia(n: Notification): Boolean {
            val template = template(n)
            return template in MEDIA_TEMPLATES ||
                template.endsWith("MediaStyle") ||
                n.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
        }

        fun template(n: Notification): String {
            val extras = n.extras ?: return ""
            return extras.getString(Notification.EXTRA_TEMPLATE) ?: extras.getString(EXTRA_COMPAT_TEMPLATE) ?: ""
        }
    }
}
