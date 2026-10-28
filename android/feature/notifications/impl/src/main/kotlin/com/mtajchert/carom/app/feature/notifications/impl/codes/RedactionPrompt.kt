package com.mtajchert.carom.app.feature.notifications.impl.codes

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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

/** When the prompt last showed and whether the user muted it. Survives process restarts. */
interface RedactionPromptStore {
    var lastShownAt: Long?
    var muted: Boolean
}

class PrefsRedactionPromptStore @Inject constructor(
    @ApplicationContext context: Context,
) : RedactionPromptStore {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override var lastShownAt: Long?
        get() = prefs.getLong(KEY_LAST_SHOWN, NEVER).takeIf { it != NEVER }
        set(value) = prefs.edit().apply { if (value == null) remove(KEY_LAST_SHOWN) else putLong(KEY_LAST_SHOWN, value) }.apply()

    override var muted: Boolean
        get() = prefs.getBoolean(KEY_MUTED, false)
        set(value) = prefs.edit().putBoolean(KEY_MUTED, value).apply()

    private companion object {
        const val PREFS = "carom_code_forwarding_prompt"
        const val KEY_LAST_SHOWN = "last_shown_at"
        const val KEY_MUTED = "muted"
        const val NEVER = Long.MIN_VALUE
    }
}

/**
 * Offers code forwarding when [com.mtajchert.carom.app.feature.notifications.impl.RedactionDetector]
 * fires (spec §5.2). At most once per [COOLDOWN_MS], never after the user picks "Don't show again",
 * and only on Android 15+. The prompt never carries notification content.
 *
 * Codes hidden despite an association usually mean the listener bound before the association
 * existed, so the first time per process it rebinds the listener instead of prompting.
 */
@Singleton
class RedactionPrompt @Inject constructor(
    private val access: CodeForwardingAccess,
    private val associations: AssociationApi,
    private val notifier: RedactionPromptNotifier,
    private val store: RedactionPromptStore,
    private val listener: ListenerReconnector,
) {
    @Volatile
    private var reconnected = false

    fun onRedacted(now: Long = System.currentTimeMillis()) {
        val state = access.state()
        if (state == CodeForwardingState.UNSUPPORTED) return
        val stillRedacted = state == CodeForwardingState.ENABLED || associations.hasAssociation()
        if (stillRedacted && !reconnected) {
            reconnected = true
            if (listener.reconnect()) return
        }
        if (store.muted) return
        synchronized(this) {
            val last = store.lastShownAt
            if (last != null && now - last in 0 until COOLDOWN_MS) return
            store.lastShownAt = now
        }
        notifier.show(stillRedacted = stillRedacted)
    }

    companion object {
        const val COOLDOWN_MS = 24 * 60 * 60 * 1000L
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
        val mute = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, RedactionPromptMuteReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.feature_notifications_ic_notification)
            .setContentTitle(context.getString(R.string.feature_notifications_codes_prompt_title))
            .setContentText(context.getString(R.string.feature_notifications_codes_prompt_text))
            .setContentIntent(pending)
            .addAction(0, context.getString(R.string.feature_notifications_codes_prompt_mute), mute)
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

/** "Don't show again" on the prompt: mutes it for good and removes it. */
class RedactionPromptMuteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PrefsRedactionPromptStore(context.applicationContext).muted = true
        NotificationManagerCompat.from(context).cancel(AndroidRedactionPromptNotifier.NOTIFICATION_ID)
    }
}
