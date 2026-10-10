package com.mtajchert.carom.app.feature.clipboard.impl

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Tells the user a clipboard arrived (docs/CLIENTS.md "Others -> phone"). */
interface ClipboardArrivalNotifier {
    fun show(fromName: String, text: String, sensitive: Boolean)
}

/** A quiet notification on its own low-importance channel, with a "Copy again" action. */
class AndroidClipboardArrivalNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : ClipboardArrivalNotifier {

    override fun show(fromName: String, text: String, sensitive: Boolean) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel()
        val body = if (sensitive) context.getString(R.string.feature_clipboard_arrived_sensitive) else firstLine(text)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.feature_clipboard_ic_notification)
            .setContentTitle(context.getString(R.string.feature_clipboard_arrived_title, fromName))
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setAutoCancel(true)
            .setTimeoutAfter(TIMEOUT_MS)
        if (!sensitive) {
            // Sensitive text is never put into an intent extra; it can only be copied from the clipboard itself.
            val copy = Intent(context, CopyAgainReceiver::class.java).putExtra(CopyAgainReceiver.EXTRA_TEXT, text)
            val pending = PendingIntent.getBroadcast(context, 0, copy, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(0, context.getString(R.string.feature_clipboard_copy_again), pending)
        }
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.feature_clipboard_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = context.getString(R.string.feature_clipboard_channel_description) }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "clipboard_arrivals"
        const val NOTIFICATION_ID = 0xC11B
        private const val TIMEOUT_MS = 10 * 60_000L
        private const val MAX_LINE = 120

        /** The first non-blank line, cut to a notification-sized length. */
        fun firstLine(text: String): String =
            text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim().let {
                if (it.length > MAX_LINE) it.take(MAX_LINE - 1) + "…" else it
            }
    }
}

/** Handles the "Copy again" action. Not exported: only our own notification can reach it. */
class CopyAgainReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        ClipboardAccess.write(context, text, sensitive = false)
        NotificationManagerCompat.from(context).cancel(AndroidClipboardArrivalNotifier.NOTIFICATION_ID)
    }

    companion object {
        const val EXTRA_TEXT = "text"
    }
}
