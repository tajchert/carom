package com.mtajchert.carom.app.feature.notifications.impl

import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import com.mtajchert.carom.app.core.protocol.MessageBodies
import java.security.MessageDigest

/** Where an image goes in the `notification.posted@1` body (V2 spec §3.3). */
sealed interface ImageSlot {
    data object AppIcon : ImageSlot
    data object SmallIcon : ImageSlot
    data object LargeIcon : ImageSlot
    data object Picture : ImageSlot
    data object ConversationIcon : ImageSlot
    data object TrackerIcon : ImageSlot
    data object StartIcon : ImageSlot
    data object EndIcon : ImageSlot
    data object CallPersonIcon : ImageSlot

    /** `messages[index].sender.icon`, by position in the snapshot's message list. */
    data class SenderIcon(val index: Int) : ImageSlot

    /** `messages[index].image`, by position in the snapshot's message list. */
    data class MessageImage(val index: Int) : ImageSlot

    /** App and small icons are the same for every notification of an app, so they use convergent keys (spec §3.2). */
    val convergent: Boolean get() = this == AppIcon || this == SmallIcon
}

/** A reference to an image the notification carries. Nothing is loaded until [ImageLoader] runs. */
sealed interface ImageSource {
    class FromIcon(val icon: Icon) : ImageSource
    class FromBitmap(val bitmap: Bitmap) : ImageSource
    class FromUri(val uri: Uri) : ImageSource

    /** The posting app's launcher icon; [info] is the `ApplicationInfo` from the extras when the package is not visible. */
    class FromApp(val packageName: String, val info: ApplicationInfo?) : ImageSource
}

/** A downscaled, WebP-encoded image ready to be sealed and uploaded. */
class EncodedImage(val bytes: ByteArray, val w: Int, val h: Int, val mime: String = "image/webp") {
    /** Lower-case hex SHA-256 of [bytes]: the key for deduplication and for the content hash. */
    val digest: String by lazy { sha256Hex(bytes) }

    override fun toString() = "EncodedImage(${w}x$h, ${bytes.size} bytes)"
}

internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

/** Lower-case hex of these bytes, two digits per byte. */
internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** One message of a conversation, without its images (they are in [NotificationSnapshot.imageSources] by index). */
data class SnapMessage(val senderName: String?, val senderKey: String?, val text: String, val ts: Long) {
    override fun toString() = "SnapMessage(ts=$ts)" // never log content
}

data class SnapConversation(val title: String?, val isGroup: Boolean) {
    override fun toString() = "SnapConversation(isGroup=$isGroup)" // never log content
}

data class SnapProgress(
    val value: Int,
    val max: Int,
    val indeterminate: Boolean,
    val segments: List<MessageBodies.Segment> = emptyList(),
    val points: List<MessageBodies.Point> = emptyList(),
) {
    /** A determinate bar that reached its end; this skips the update throttle. */
    val complete: Boolean get() = !indeterminate && max > 0 && value >= max
}

data class SnapCall(
    /** `incoming`, `ongoing`, `screening` or `unknown`. */
    val type: String,
    val personName: String? = null,
    val isVideo: Boolean? = null,
    val verification: String? = null,
) {
    override fun toString() = "SnapCall(type=$type)" // never log content
}

/** Everything beyond V1's title/text that spec §3.3 sends. Text fields are plain, untruncated text. */
data class RichContent(
    val template: String = TEMPLATE_STANDARD,
    val color: Int? = null,
    val colorized: Boolean? = null,
    val bigText: String? = null,
    val lines: List<String> = emptyList(),
    val summaryText: String? = null,
    val pictureDesc: String? = null,
    val conversation: SnapConversation? = null,
    /** Oldest first, at most [MAX_MESSAGES]. */
    val messages: List<SnapMessage> = emptyList(),
    /** At most [MAX_ACTIONS]; `index` is the position in `Notification.actions`. */
    val actions: List<MessageBodies.Action> = emptyList(),
    val progress: SnapProgress? = null,
    val live: MessageBodies.Live? = null,
    val whenMs: Long? = null,
    val showWhen: Boolean? = null,
    val chronometer: MessageBodies.Chronometer? = null,
    val call: SnapCall? = null,
    val group: MessageBodies.Group? = null,
    val channel: MessageBodies.Channel? = null,
) {
    override fun toString() = "RichContent(template=$template)" // never log content

    companion object {
        const val TEMPLATE_STANDARD = "standard"
        const val TEMPLATE_BIG_TEXT = "big_text"
        const val TEMPLATE_INBOX = "inbox"
        const val TEMPLATE_MESSAGING = "messaging"
        const val TEMPLATE_BIG_PICTURE = "big_picture"
        const val TEMPLATE_PROGRESS = "progress"
        const val TEMPLATE_CALL = "call"
        const val TEMPLATE_MEDIA = "media"
        const val TEMPLATE_CUSTOM = "custom"

        const val MAX_MESSAGES = 6
        const val MAX_LINES = 7
        const val MAX_ACTIONS = 6
        const val MAX_CHOICES = 5
        const val MAX_SEGMENTS = 10
        const val MAX_POINTS = 4
    }
}
