package com.mtajchert.carom.app.feature.notifications.impl

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.os.BundleCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads a [StatusBarNotification] into a [NotificationSnapshot] (docs/CLIENTS.md "Mapping").
 * Called off the main thread; touches only the notification's extras, the ranking and a
 * cached application label.
 */
class NotificationExtractor @Inject constructor(
    private val labels: AppLabelResolver,
    private val redaction: RedactionDetector,
) {
    fun extract(sbn: StatusBarNotification, ranking: NotificationListenerService.Ranking?): NotificationSnapshot {
        val n = sbn.notification
        val extras = n.extras ?: Bundle.EMPTY
        val flags = n.flags

        val last = lastMessage(n)
        // For a conversation the text already names the sender, so its title is the better title.
        val title = if (last != null) {
            extras.text(Notification.EXTRA_CONVERSATION_TITLE) ?: extras.text(Notification.EXTRA_TITLE)
        } else {
            extras.text(Notification.EXTRA_TITLE) ?: extras.text(Notification.EXTRA_CONVERSATION_TITLE)
        }
        val bigText = extras.text(Notification.EXTRA_BIG_TEXT)
        val plainText = extras.text(Notification.EXTRA_TEXT)
        val subText = extras.text(Notification.EXTRA_SUB_TEXT)

        // Android 15+ swaps the content for a placeholder when we may not see it.
        val redacted = listOf(title, last?.body, bigText, plainText, subText).any(redaction::isRedacted)
        val text = if (last != null) {
            last.takeUnless { redaction.isRedacted(it.body) }?.formatted()
        } else {
            listOf(bigText, plainText).firstOrNull { it != null && !redaction.isRedacted(it) }
        }

        return NotificationSnapshot(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = labels.label(sbn.packageName, extras),
            postedAt = sbn.postTime,
            ongoing = flags and Notification.FLAG_ONGOING_EVENT != 0,
            foregroundService = flags and Notification.FLAG_FOREGROUND_SERVICE != 0,
            groupSummary = flags and Notification.FLAG_GROUP_SUMMARY != 0,
            localOnly = flags and Notification.FLAG_LOCAL_ONLY != 0,
            mediaStyle = isMedia(n, extras),
            importance = ranking?.importance?.takeIf { it != NOTIFICATION_IMPORTANCE_UNSPECIFIED },
            title = title.unlessRedacted(),
            text = text,
            subText = subText.unlessRedacted(),
            category = n.category,
            redacted = redacted,
        )
    }

    private fun String?.unlessRedacted(): String? = this?.takeUnless { redaction.isRedacted(it) }

    private class LastMessage(val sender: String?, val body: String) {
        /** "sender: text"; the sender is omitted when unknown. */
        fun formatted() = if (sender == null) body else "$sender: $body"
    }

    /** For `MessagingStyle`, the last message. */
    private fun lastMessage(n: Notification): LastMessage? {
        val style = try {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        } catch (_: RuntimeException) {
            null // malformed extras from another app must not break forwarding
        } ?: return null
        val message = style.messages.lastOrNull() ?: return null
        val body = message.text?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val sender = (message.person ?: style.user).name?.toString()?.takeIf { it.isNotBlank() }
        return LastMessage(sender, body)
    }

    private fun isMedia(n: Notification, extras: Bundle): Boolean {
        val template = extras.getString(Notification.EXTRA_TEMPLATE)
        return template in MEDIA_TEMPLATES ||
            extras.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
            n.category == Notification.CATEGORY_TRANSPORT
    }

    private fun Bundle.text(key: String): String? = getCharSequence(key)?.toString()?.takeIf { it.isNotBlank() }

    private companion object {
        const val NOTIFICATION_IMPORTANCE_UNSPECIFIED = -1000
        val MEDIA_TEMPLATES = setOf(
            "android.app.Notification\$MediaStyle",
            "android.app.Notification\$DecoratedMediaCustomViewStyle",
        )
    }
}

/**
 * Detects Android 15's sensitive-content redaction: the platform replaces the text with its
 * `redacted_notification_message` string ("Sensitive notification content hidden").
 */
class RedactionDetector(private val placeholders: Set<String>, private val sdkInt: Int = Build.VERSION.SDK_INT) {

    fun isRedacted(text: String?): Boolean =
        sdkInt >= Build.VERSION_CODES.VANILLA_ICE_CREAM && text != null && text.trim() in placeholders

    companion object {
        const val ENGLISH_PLACEHOLDER = "Sensitive notification content hidden"

        /** The system's localized placeholder (an internal resource, looked up by name) plus English. */
        @SuppressLint("DiscouragedApi")
        fun systemPlaceholders(): Set<String> {
            val localized = try {
                val res = Resources.getSystem()
                val id = res.getIdentifier("redacted_notification_message", "string", "android")
                if (id != 0) res.getString(id) else null
            } catch (_: Resources.NotFoundException) {
                null
            }
            return setOfNotNull(ENGLISH_PLACEHOLDER, localized?.trim())
        }
    }
}

/**
 * Application labels for packages seen posting notifications, cached per process. Without
 * `QUERY_ALL_PACKAGES` a package may be invisible to us, so the label falls back to the
 * `ApplicationInfo` the platform puts in the notification's extras, then to the package name.
 */
@Singleton
class AppLabelResolver @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val cache = HashMap<String, String>()

    fun label(packageName: String, extras: Bundle?): String {
        synchronized(cache) { cache[packageName] }?.let { return it }
        val pm = context.packageManager
        val label = fromPackageManager(pm, packageName) ?: fromExtras(pm, extras) ?: packageName
        synchronized(cache) { cache[packageName] = label }
        return label
    }

    private fun fromPackageManager(pm: PackageManager, packageName: String): String? = try {
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString().takeIf { it.isNotBlank() }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun fromExtras(pm: PackageManager, extras: Bundle?): String? {
        val info = extras?.let { BundleCompat.getParcelable(it, EXTRA_APP_INFO, ApplicationInfo::class.java) } ?: return null
        return try {
            info.loadLabel(pm).toString().takeIf { it.isNotBlank() }
        } catch (_: RuntimeException) {
            null
        }
    }

    private companion object {
        /** `Notification.EXTRA_BUILDER_APPLICATION_INFO`, set by `Notification.Builder` (hidden constant). */
        const val EXTRA_APP_INFO = "android.appInfo"
    }
}
