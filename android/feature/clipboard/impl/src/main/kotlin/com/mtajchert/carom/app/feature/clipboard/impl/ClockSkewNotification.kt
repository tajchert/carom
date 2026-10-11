package com.mtajchert.carom.app.feature.clipboard.impl

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mtajchert.carom.app.core.data.runtime.SyncStartHook
import com.mtajchert.carom.app.core.hub.ClockDrift
import com.mtajchert.carom.app.core.hub.ClockSkewTracker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Posts or removes the "this phone's clock is off" notification. */
interface ClockSkewNotifier {
    fun show(drift: ClockDrift)
    fun hide()
}

/**
 * Posts ONE notification per clock-drift episode: when the drift appears, not on every response
 * while it lasts; it is removed again when the drift clears.
 */
class ClockSkewNotificationController @Inject constructor(
    private val tracker: ClockSkewTracker,
    private val notifier: ClockSkewNotifier,
) : SyncStartHook {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onAppStart() {
        scope.launch { collect() }
    }

    internal suspend fun collect() {
        tracker.drift.distinctUntilChangedBy { it != null }.collect { drift ->
            if (drift != null) notifier.show(drift) else notifier.hide()
        }
    }
}

class AndroidClockSkewNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : ClockSkewNotifier {

    override fun show(drift: ClockDrift) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel()
        val open = PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            Intent(Settings.ACTION_DATE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = context.resources.getQuantityString(
            if (drift.localAhead) R.plurals.feature_clipboard_clock_ahead else R.plurals.feature_clipboard_clock_behind,
            drift.minutes.toInt(),
            drift.minutes,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.feature_clipboard_ic_notification)
            .setContentTitle(context.getString(R.string.feature_clipboard_clock_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(open)
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
            context.getString(R.string.feature_clipboard_clock_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.feature_clipboard_clock_channel_description) }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "carom_clock"
        const val NOTIFICATION_ID = 0xCA21
        private const val REQUEST_OPEN = 3
    }
}
