package com.mtajchert.carom.app.feature.notifications.impl

import android.app.KeyguardManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.mtajchert.carom.app.feature.notifications.impl.media.MediaBridge
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Carom's notification listener (docs/CLIENTS.md "Notifications"). The system binds it while
 * notification access is granted; there is no other long-running service.
 *
 * Callbacks arrive on the main thread and only enqueue into a [NotificationForwarder]; reading
 * the notification, filtering, Room and the network all run on [Dispatchers.Default]/IO in a
 * scope that lives exactly as long as the listener connection.
 */
@AndroidEntryPoint
class CaromListenerService : NotificationListenerService() {

    @Inject lateinit var forwarderFactory: NotificationForwarder.Factory

    @Inject lateinit var extractor: NotificationExtractor

    @Inject lateinit var bridge: ActiveNotificationsBridge

    @Inject lateinit var mediaBridge: MediaBridge

    private var scope: CoroutineScope? = null
    private var forwarder: NotificationForwarder? = null

    private val control = object : ActiveNotificationsBridge.Listener {
        override fun isActive(key: String): Boolean = try {
            getActiveNotifications(arrayOf(key))?.any { it.key == key } == true
        } catch (_: SecurityException) {
            false // access was revoked between the check and the call
        }

        override fun cancel(key: String) {
            try {
                cancelNotification(key)
            } catch (_: SecurityException) {
                Log.i(TAG, "dismiss_unavailable")
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        scope?.cancel()
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = newScope
        val env = AndroidForwardingEnvironment()
        val f = forwarderFactory.create(newScope, env)
        forwarder = f
        bridge.attach(control)
        mediaBridge.start(newScope, env)
        // Media that is already playing when the listener (re)connects.
        newScope.launch { activeNotificationsOrEmpty().forEach(mediaBridge::onPosted) }
        // Removals that happened while we were not bound (reconnect, process death) are sent now.
        f.reconcile {
            try {
                activeNotifications?.mapTo(HashSet()) { it.key }
            } catch (_: SecurityException) {
                null // access was revoked; keep the keys for the next connection
            } catch (_: RuntimeException) {
                null // the binder went away before we read the list
            }
        }
    }

    override fun onListenerDisconnected() {
        stop()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        val f = forwarder ?: return
        // Cheapest possible check on the main thread; everything else happens on the consumer.
        if (sbn.packageName == packageName) return
        mediaBridge.onPosted(sbn)
        f.onPosted {
            val ranking = Ranking()
            val map = rankingMap ?: currentRanking
            extractor.extract(sbn, ranking.takeIf { map?.getRanking(sbn.key, it) == true })
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = onNotificationPosted(sbn, null)

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        mediaBridge.onRemoved(sbn)
        forwarder?.onRemoved(sbn.key)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) = onNotificationRemoved(sbn, null)

    private fun activeNotificationsOrEmpty(): List<StatusBarNotification> = try {
        activeNotifications?.toList().orEmpty()
    } catch (_: SecurityException) {
        emptyList() // access was revoked
    } catch (_: RuntimeException) {
        emptyList() // the binder went away
    }

    private fun stop() {
        bridge.detach(control)
        mediaBridge.stop()
        forwarder = null
        scope?.cancel()
        scope = null
    }

    private inner class AndroidForwardingEnvironment : ForwardingEnvironment {
        private val keyguard = getSystemService(KeyguardManager::class.java)

        override val ownPackage: String = packageName
        override val hiddenText: String = getString(R.string.feature_notifications_hidden_text)

        override fun isScreenLocked(): Boolean = keyguard?.isKeyguardLocked == true

        override fun log(event: String, detail: String?) {
            Log.i(TAG, if (detail == null) event else "$event $detail")
        }
    }

    private companion object {
        const val TAG = "CaromListener"
    }
}

/**
 * Lets the `notification.dismiss@1` handler reach the connected listener, if any. The listener
 * attaches itself while the system has it bound.
 */
@javax.inject.Singleton
class ActiveNotificationsBridge @Inject constructor() {
    interface Listener {
        fun isActive(key: String): Boolean
        fun cancel(key: String)
    }

    @Volatile
    private var listener: Listener? = null

    fun attach(l: Listener) {
        listener = l
    }

    fun detach(l: Listener) {
        if (listener === l) listener = null
    }

    /** Cancels [key] if it is still showing. Returns false when nothing was cancelled. */
    fun dismiss(key: String): Boolean {
        val l = listener ?: return false
        if (!l.isActive(key)) return false
        l.cancel(key)
        return true
    }
}
