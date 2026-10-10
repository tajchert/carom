package com.mtajchert.carom.app.feature.clipboard.impl

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mtajchert.carom.app.core.data.repository.SessionRepository
import com.mtajchert.carom.app.core.data.repository.UserPreferencesRepository
import com.mtajchert.carom.app.core.data.runtime.SyncStartHook
import com.mtajchert.carom.app.core.model.SessionState
import com.mtajchert.carom.app.feature.clipboard.api.ClipboardSend
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Posts or removes the optional ongoing "Carom" notification. */
interface OngoingNotifier {
    fun show()
    fun hide()
}

/**
 * The optional ongoing "Carom" notification with a "Send clipboard" action (docs/CLIENTS.md
 * "Phone → others", off by default). Shown while the setting is on and this phone is paired.
 * It is a plain notification, not a foreground service: no process is kept alive for it.
 */
class OngoingNotificationController @Inject constructor(
    private val preferences: UserPreferencesRepository,
    private val session: SessionRepository,
    private val notifier: OngoingNotifier,
) : SyncStartHook {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** True while the notification should be on screen. */
    internal fun wanted(): Flow<Boolean> = combine(
        preferences.userPreferences.map { it.ongoingNotification },
        session.session.map { it is SessionState.Paired },
    ) { enabled, paired -> enabled && paired }.distinctUntilChanged()

    override fun onAppStart() {
        scope.launch { wanted().collect { if (it) notifier.show() else notifier.hide() } }
    }
}

class AndroidOngoingNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : OngoingNotifier {

    override fun show() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel()
        val send = PendingIntent.getActivity(
            context,
            REQUEST_SEND,
            ClipboardSend.intent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, REQUEST_OPEN, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.feature_clipboard_ic_notification)
            .setContentTitle(context.getString(R.string.feature_clipboard_ongoing_title))
            .setContentText(context.getString(R.string.feature_clipboard_ongoing_text))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.feature_clipboard_send_now), send)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    override fun hide() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.feature_clipboard_ongoing_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = context.getString(R.string.feature_clipboard_ongoing_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "carom_ongoing"
        const val NOTIFICATION_ID = 0xCA20
        private const val REQUEST_SEND = 1
        private const val REQUEST_OPEN = 2
    }
}
