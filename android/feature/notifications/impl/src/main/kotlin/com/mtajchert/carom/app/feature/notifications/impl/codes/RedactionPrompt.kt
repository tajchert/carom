package com.mtajchert.carom.app.feature.notifications.impl.codes

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mtajchert.carom.app.feature.notifications.api.CodeForwardingAccess
import com.mtajchert.carom.app.feature.notifications.api.CodeForwardingState
import com.mtajchert.carom.app.feature.notifications.impl.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Shows the "enable code forwarding" prompt. A seam so the cooldown is tested without notifications. */
fun interface RedactionPromptNotifier {
    /** [stillRedacted]: codes are hidden although the switch or an association already exists. */
    fun show(stillRedacted: Boolean)
}

/**
 * Offers code forwarding when [com.mtajchert.carom.app.feature.notifications.impl.RedactionDetector]
 * fires (spec §5.2). At most once per [COOLDOWN_MS] per process, and only on Android 15+. The
 * prompt never carries notification content.
 */
@Singleton
class RedactionPrompt @Inject constructor(
    private val access: CodeForwardingAccess,
    private val associations: AssociationApi,
    private val notifier: RedactionPromptNotifier,
) {
    private var lastShownAt = NEVER

    fun onRedacted(now: Long = System.currentTimeMillis()) {
        val state = access.state()
        if (state == CodeForwardingState.UNSUPPORTED) return
        synchronized(this) {
            if (lastShownAt != NEVER && now - lastShownAt < COOLDOWN_MS) return
            lastShownAt = now
        }
        notifier.show(stillRedacted = state == CodeForwardingState.ENABLED || associations.hasAssociation())
    }

    companion object {
        const val COOLDOWN_MS = 24 * 60 * 60 * 1000L
        private const val NEVER = Long.MIN_VALUE
    }
}

/** A quiet notification on its own low-importance channel; tapping it opens [CodeForwardingActivity]. */
class AndroidRedactionPromptNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : RedactionPromptNotifier {

    override fun show(stillRedacted: Boolean) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel()
        val open = Intent()
            .setClassName(context.packageName, AndroidCodeForwardingAccess.ACTIVITY_CLASS)
            .putExtra(CodeForwardingActivity.EXTRA_STILL_REDACTED, stillRedacted)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.feature_notifications_ic_notification)
            .setContentTitle(context.getString(R.string.feature_notifications_codes_prompt_title))
            .setContentText(context.getString(R.string.feature_notifications_codes_prompt_text))
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.feature_notifications_codes_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = context.getString(R.string.feature_notifications_codes_channel_description) }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "code_forwarding_prompt"
        const val NOTIFICATION_ID = 0xC0DE
    }
}
