package com.mtajchert.carom.app.feature.notifications.impl

import com.mtajchert.carom.app.core.protocol.MessageBodies
import kotlinx.serialization.json.JsonObject

/** Maps a [NotificationSnapshot] to SPEC §4 bodies (docs/CLIENTS.md "Mapping"). */
object NotificationMapper {
    /** Text fields are truncated at this many characters. */
    const val MAX_TEXT_CHARS = 4000

    /**
     * `notification.posted@1`. With [hideContent] only the app and [hiddenText] ("New
     * notification") leave the phone; title, sub text and the real text stay local.
     */
    fun posted(
        n: NotificationSnapshot,
        appName: String,
        hideContent: Boolean,
        hiddenText: String,
        maxChars: Int = MAX_TEXT_CHARS,
    ): MessageBodies.NotificationPosted = MessageBodies.NotificationPosted(
        key = n.key,
        app = MessageBodies.App(id = n.packageName, name = appName.ifBlank { n.packageName }),
        title = if (hideContent) null else n.title.clean(maxChars),
        text = if (hideContent) hiddenText else n.text.clean(maxChars),
        subText = if (hideContent) null else n.subText.clean(maxChars),
        category = n.category?.takeIf { it.isNotBlank() },
        postedAt = n.postedAt.coerceAtLeast(0),
        redacted = !hideContent && n.redacted,
    )

    fun postedJson(body: MessageBodies.NotificationPosted): JsonObject =
        MessageBodies.toJson(MessageBodies.NotificationPosted.serializer(), body)

    fun removedJson(key: String): JsonObject =
        MessageBodies.toJson(MessageBodies.NotificationRemoved.serializer(), MessageBodies.NotificationRemoved(key))

    /** Blank becomes absent; long text is cut at [max] characters without splitting a surrogate pair. */
    internal fun String?.clean(max: Int): String? {
        val s = this?.takeIf { it.isNotBlank() } ?: return null
        return truncate(s, max)
    }

    fun truncate(s: String, max: Int = MAX_TEXT_CHARS): String {
        if (s.length <= max) return s
        val end = if (Character.isHighSurrogate(s[max - 1])) max - 1 else max
        return s.substring(0, end)
    }
}
