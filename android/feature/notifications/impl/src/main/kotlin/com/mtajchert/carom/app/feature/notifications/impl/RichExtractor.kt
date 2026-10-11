package com.mtajchert.carom.app.feature.notifications.impl

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Person as PlatformPerson
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.os.BundleCompat
import com.mtajchert.carom.app.core.protocol.MessageBodies
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads a [StatusBarNotification] into a [NotificationSnapshot] (V2 spec §5.1). A pure function of
 * the notification and its channel importance: it keeps references to images ([ImageSource]) but
 * loads none, so Robolectric-built notifications can drive it. Called off the main thread.
 *
 * Extras that are hidden, newer than minSdk or written by other apps are read inside try/catch:
 * a failure omits that field and the rest of the notification is still read (spec §7).
 */
@SuppressLint("InlinedApi")
class RichExtractor @Inject constructor(
    private val labels: AppLabelResolver,
    private val redaction: RedactionDetector,
) {
    fun extract(sbn: StatusBarNotification, ranking: NotificationListenerService.Ranking?): NotificationSnapshot =
        extractWithImportance(sbn, ranking?.importance?.takeIf { it != NOTIFICATION_IMPORTANCE_UNSPECIFIED })

    fun extractWithImportance(sbn: StatusBarNotification, importance: Int?): NotificationSnapshot {
        val n = sbn.notification
        val extras = n.extras ?: Bundle.EMPTY
        val flags = n.flags
        val foregroundService = flags and Notification.FLAG_FOREGROUND_SERVICE != 0
        val media = isMedia(n, extras)
        val sources = LinkedHashMap<ImageSlot, ImageSource>()
        val template = template(n, extras)

        val messaging = if (template == RichContent.TEMPLATE_MESSAGING) guarded { readMessaging(extras, sources) } else null
        val lastBody = messaging?.lastBody

        // For a conversation the text already names the sender, so its title is the better title.
        val title = if (lastBody != null) {
            extras.text(Notification.EXTRA_CONVERSATION_TITLE) ?: extras.text(Notification.EXTRA_TITLE)
        } else {
            extras.text(Notification.EXTRA_TITLE) ?: extras.text(Notification.EXTRA_CONVERSATION_TITLE)
        }
        val plainText = extras.text(Notification.EXTRA_TEXT)
        val bigText = extras.text(Notification.EXTRA_BIG_TEXT)
        val subText = extras.text(Notification.EXTRA_SUB_TEXT)
        val summaryText = extras.text(Notification.EXTRA_SUMMARY_TEXT)
        val lines = readLines(extras)

        // Android 15+ swaps the content for a placeholder when we may not see it.
        val redacted = listOf(title, lastBody, plainText, bigText, subText, summaryText).any(redaction::isRedacted) ||
            lines.any(redaction::isRedacted) || messaging?.redacted == true
        val text = if (messaging != null && lastBody != null) {
            lastBody.takeUnless(redaction::isRedacted)?.let { messaging.formatted() }
        } else {
            listOf(plainText, bigText).firstOrNull { it != null && !redaction.isRedacted(it) }
        }

        sources[ImageSlot.AppIcon] = ImageSource.FromApp(sbn.packageName, appInfo(extras))
        guarded { n.getSmallIcon() }?.let { sources[ImageSlot.SmallIcon] = ImageSource.FromIcon(it) }
        guarded { n.getLargeIcon() }?.let {
            // A conversation's large icon is the chat's avatar, not a picture beside the text.
            sources[if (messaging != null) ImageSlot.ConversationIcon else ImageSlot.LargeIcon] = ImageSource.FromIcon(it)
        }
        if (template == RichContent.TEMPLATE_BIG_PICTURE) picture(extras)?.let { sources[ImageSlot.Picture] = it }

        val rich = RichContent(
            template = template,
            color = n.color.takeIf { it != Notification.COLOR_DEFAULT },
            colorized = true.takeIf { extras.getBoolean(Notification.EXTRA_COLORIZED) && (foregroundService || media) },
            bigText = bigText.takeIf { it != null && !redaction.isRedacted(it) && it != text },
            lines = lines.filterNot(redaction::isRedacted).take(RichContent.MAX_LINES),
            summaryText = summaryText?.takeUnless(redaction::isRedacted),
            conversation = messaging?.conversation,
            messages = messaging?.messages.orEmpty(),
            pictureDesc = extras.text(Notification.EXTRA_PICTURE_CONTENT_DESCRIPTION),
            actions = guarded { readActions(n) }.orEmpty(),
            canOpen = n.contentIntent != null,
            whenMs = n.`when`.takeIf { it > 0 },
            showWhen = extras.getBoolean(Notification.EXTRA_SHOW_WHEN),
            chronometer = if (extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)) {
                MessageBodies.Chronometer(countDown = extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
            } else {
                null
            },
            group = readGroup(n, flags),
            channel = n.channelId?.let { id -> importance?.let { MessageBodies.Channel(id, it) } },
        )

        return NotificationSnapshot(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = labels.label(sbn.packageName, extras),
            postedAt = sbn.postTime,
            ongoing = flags and Notification.FLAG_ONGOING_EVENT != 0,
            foregroundService = foregroundService,
            groupSummary = flags and Notification.FLAG_GROUP_SUMMARY != 0,
            localOnly = flags and Notification.FLAG_LOCAL_ONLY != 0,
            mediaStyle = media,
            importance = importance,
            title = title.unlessRedacted(),
            text = text,
            subText = subText.unlessRedacted(),
            category = n.category,
            redacted = redacted,
            autogroupSummary = flags and Notification.FLAG_GROUP_SUMMARY != 0 && sbn.overrideGroupKey != null,
            promoted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && flags and FLAG_PROMOTED_ONGOING != 0,
            rich = rich,
            imageSources = sources,
        )
    }

    private fun String?.unlessRedacted(): String? = this?.takeUnless { redaction.isRedacted(it) }

    /** The template from `EXTRA_TEMPLATE`, matched by the class-name suffix (platform and compat classes alike). */
    private fun template(n: Notification, extras: Bundle): String {
        val style = extras.getString(Notification.EXTRA_TEMPLATE)?.substringAfterLast('$')?.substringAfterLast('.')
        return when (style) {
            "BigTextStyle" -> RichContent.TEMPLATE_BIG_TEXT
            "InboxStyle" -> RichContent.TEMPLATE_INBOX
            "MessagingStyle" -> RichContent.TEMPLATE_MESSAGING
            "BigPictureStyle" -> RichContent.TEMPLATE_BIG_PICTURE
            "ProgressStyle" -> RichContent.TEMPLATE_PROGRESS
            "CallStyle" -> RichContent.TEMPLATE_CALL
            "MediaStyle", "DecoratedMediaCustomViewStyle" -> RichContent.TEMPLATE_MEDIA
            "DecoratedCustomViewStyle" -> RichContent.TEMPLATE_CUSTOM
            else -> if (hasCustomViews(n)) RichContent.TEMPLATE_CUSTOM else RichContent.TEMPLATE_STANDARD
        }
    }

    @Suppress("DEPRECATION")
    private fun hasCustomViews(n: Notification): Boolean = n.contentView != null || n.bigContentView != null

    private fun readLines(extras: Bundle): List<String> =
        guarded { extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES) }.orEmpty()
            .mapNotNull { it?.toString()?.takeIf { s -> s.isNotBlank() } }

    /** At most [RichContent.MAX_ACTIONS] actions; `index` stays the position in `Notification.actions`. */
    private fun readActions(n: Notification): List<MessageBodies.Action> =
        n.actions.orEmpty().withIndex().mapNotNull { (index, action) ->
            val title = action.title?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val input = action.remoteInputs?.firstOrNull { it.allowFreeFormInput }
            MessageBodies.Action(
                index = index,
                title = title,
                semantic = action.semanticAction.takeIf { it != Notification.Action.SEMANTIC_ACTION_NONE },
                reply = input?.let {
                    MessageBodies.ReplySpec(
                        label = it.label?.toString()?.takeIf { l -> l.isNotBlank() },
                        choices = it.choices?.mapNotNull { c -> c?.toString()?.takeIf { s -> s.isNotBlank() } }
                            ?.take(RichContent.MAX_CHOICES)
                            ?.ifEmpty { null },
                    )
                },
            )
        }.take(RichContent.MAX_ACTIONS)

    private fun readGroup(n: Notification, flags: Int): MessageBodies.Group? {
        val key = n.group?.takeIf { it.isNotBlank() } ?: return null
        return MessageBodies.Group(
            key = key,
            summary = flags and Notification.FLAG_GROUP_SUMMARY != 0,
            sortKey = n.sortKey?.takeIf { it.isNotBlank() },
            alert = when (n.groupAlertBehavior) {
                Notification.GROUP_ALERT_SUMMARY -> "summary"
                Notification.GROUP_ALERT_CHILDREN -> "children"
                else -> "all"
            },
        )
    }

    private fun picture(extras: Bundle): ImageSource? = guarded {
        BundleCompat.getParcelable(extras, Notification.EXTRA_PICTURE_ICON, Icon::class.java)?.let { ImageSource.FromIcon(it) }
            ?: BundleCompat.getParcelable(extras, Notification.EXTRA_PICTURE, Bitmap::class.java)?.let { ImageSource.FromBitmap(it) }
    }

    private fun appInfo(extras: Bundle): ApplicationInfo? =
        guarded { BundleCompat.getParcelable(extras, AppLabelResolver.EXTRA_APP_INFO, ApplicationInfo::class.java) }

    private fun isMedia(n: Notification, extras: Bundle): Boolean {
        val template = extras.getString(Notification.EXTRA_TEMPLATE)
        return template in MEDIA_TEMPLATES ||
            extras.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
            n.category == Notification.CATEGORY_TRANSPORT
    }

    private class Messaging(
        val conversation: SnapConversation,
        val messages: List<SnapMessage>,
        val lastBody: String?,
        val lastSender: String?,
        val redacted: Boolean,
    ) {
        /** "sender: text"; the sender is omitted when unknown. */
        fun formatted(): String? = lastBody?.let { body -> if (lastSender == null) body else "$lastSender: $body" }
    }

    /**
     * `MessagingStyle` messages from the platform bundles (historic first), the newest
     * [RichContent.MAX_MESSAGES]. Sender icons and message images are registered in [sources]
     * under the message's position in the returned list.
     */
    private fun readMessaging(extras: Bundle, sources: MutableMap<ImageSlot, ImageSource>): Messaging? {
        val user = BundleCompat.getParcelable(extras, Notification.EXTRA_MESSAGING_PERSON, PlatformPerson::class.java)
        val all = Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles(extras, Notification.EXTRA_HISTORIC_MESSAGES)) +
            Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles(extras, Notification.EXTRA_MESSAGES))
        val kept = all.filter { it.text?.isNotBlank() == true || it.hasImage() }.takeLast(RichContent.MAX_MESSAGES)
        if (kept.isEmpty()) return null

        var anyRedacted = false
        val messages = kept.mapIndexed { i, m ->
            val person = m.senderPerson ?: user
            person?.icon?.let { sources[ImageSlot.SenderIcon(i)] = ImageSource.FromIcon(it) }
            m.dataUri?.takeIf { m.hasImage() }?.let { sources[ImageSlot.MessageImage(i)] = ImageSource.FromUri(it) }
            val body = m.text?.toString().orEmpty()
            val hidden = redaction.isRedacted(body)
            if (hidden) anyRedacted = true
            SnapMessage(
                senderName = (person?.name ?: m.sender)?.toString()?.takeIf { it.isNotBlank() },
                senderKey = person?.key?.takeIf { it.isNotBlank() },
                text = if (hidden) "" else body,
                ts = m.timestamp.coerceAtLeast(0),
            )
        }
        val last = kept.last()
        val lastPerson = last.senderPerson ?: user
        return Messaging(
            conversation = SnapConversation(
                title = extras.text(Notification.EXTRA_CONVERSATION_TITLE),
                isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION),
            ),
            messages = messages,
            lastBody = last.text?.toString()?.takeIf { it.isNotBlank() },
            lastSender = (lastPerson?.name ?: last.sender)?.toString()?.takeIf { it.isNotBlank() },
            redacted = anyRedacted,
        )
    }

    private fun Notification.MessagingStyle.Message.hasImage(): Boolean =
        dataUri != null && dataMimeType?.startsWith("image/") == true

    private fun bundles(extras: Bundle, key: String): Array<Parcelable> =
        BundleCompat.getParcelableArray(extras, key, Bundle::class.java) ?: emptyArray()

    private fun Bundle.text(key: String): String? = getCharSequence(key)?.toString()?.takeIf { it.isNotBlank() }

    private inline fun <T> guarded(block: () -> T?): T? = try {
        block()
    } catch (_: RuntimeException) {
        null // malformed extras from another app must not break forwarding
    }

    private companion object {
        const val NOTIFICATION_IMPORTANCE_UNSPECIFIED = -1000

        /** `Notification.FLAG_PROMOTED_ONGOING` (Android 16). */
        const val FLAG_PROMOTED_ONGOING = 0x00040000
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

    internal companion object {
        /** `Notification.EXTRA_BUILDER_APPLICATION_INFO`, set by `Notification.Builder` (hidden constant). */
        const val EXTRA_APP_INFO = "android.appInfo"
    }
}
