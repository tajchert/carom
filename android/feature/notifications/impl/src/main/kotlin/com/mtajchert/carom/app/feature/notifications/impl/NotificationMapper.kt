package com.mtajchert.carom.app.feature.notifications.impl

import com.mtajchert.carom.app.core.protocol.MessageBodies
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

/** Maps a [NotificationSnapshot] plus its uploaded images to the V2 `notification.posted@1` body (spec §3.3). */
object NotificationMapper {
    /** `title`, `text`, `sub_text`, `big_text`, `summary_text` and the conversation title are cut at this many characters. */
    const val MAX_TEXT_CHARS = 4000

    /** Inbox lines, message texts and call verification text. */
    const val MAX_LINE_CHARS = 1000
    const val MAX_ACTION_TITLE_CHARS = 200
    const val MAX_NAME_CHARS = 200
    const val MAX_CHOICE_CHARS = 80

    /**
     * The compact JSON body must stay under the 65 536-byte plaintext limit once the payload
     * wrapper (`type`, `ts`, `reply_to`, about 150 bytes) is added; the rest is headroom.
     */
    const val MAX_BODY_BYTES = 65_000

    /** The smaller budget of the one retry after the engine reports `TooLarge`. */
    const val FALLBACK_BODY_BYTES = 32_000

    private const val MAX_SHRINK_ROUNDS = 128

    /**
     * `notification.posted@1`. With [hideContent] only `key`, `app` (with its icon), `posted_at` and
     * `redacted: true` leave the phone: no text, other images or actions.
     *
     * [refs] are the uploaded images by slot; a slot without a ref is simply absent. The result is
     * cut to [maxBodyBytes]: oldest messages first, then inbox lines from the end, then the longest
     * text field is halved.
     */
    fun posted(
        n: NotificationSnapshot,
        appName: String,
        hideContent: Boolean,
        refs: Map<ImageSlot, MessageBodies.BlobRef> = emptyMap(),
        maxChars: Int = MAX_TEXT_CHARS,
        maxBodyBytes: Int = MAX_BODY_BYTES,
    ): MessageBodies.NotificationPosted {
        val app = MessageBodies.App(
            id = n.packageName,
            name = appName.ifBlank { n.packageName },
            icon = refs[ImageSlot.AppIcon],
        )
        val r = n.rich
        if (hideContent) {
            return MessageBodies.NotificationPosted(
                key = n.key,
                app = app,
                postedAt = n.postedAt.coerceAtLeast(0),
                redacted = true,
            )
        }
        val body = MessageBodies.NotificationPosted(
            key = n.key,
            app = app,
            title = n.title.clean(maxChars),
            text = n.text.clean(maxChars),
            subText = n.subText.clean(maxChars),
            category = n.category?.takeIf { it.isNotBlank() },
            postedAt = n.postedAt.coerceAtLeast(0),
            redacted = n.redacted,
            ongoing = n.ongoing || n.foregroundService,
            template = r.template,
            color = r.color,
            colorized = r.colorized,
            smallIcon = refs[ImageSlot.SmallIcon],
            largeIcon = refs[ImageSlot.LargeIcon],
            picture = refs[ImageSlot.Picture],
            pictureDesc = r.pictureDesc.clean(maxChars),
            bigText = r.bigText.clean(maxChars),
            lines = r.lines.take(RichContent.MAX_LINES).mapNotNull { it.clean(MAX_LINE_CHARS) }.ifEmpty { null },
            summaryText = r.summaryText.clean(maxChars),
            conversation = r.conversation?.let {
                MessageBodies.Conversation(
                    title = it.title.clean(maxChars),
                    isGroup = it.isGroup,
                    icon = refs[ImageSlot.ConversationIcon],
                )
            },
            messages = messages(r.messages, refs),
            actions = r.actions.take(RichContent.MAX_ACTIONS).map(::clip).ifEmpty { null },
            progress = r.progress?.let { progress(it, refs) },
            live = r.live,
            `when` = r.whenMs,
            showWhen = r.showWhen,
            chronometer = r.chronometer,
            call = r.call?.let { call(it, refs) },
            group = r.group,
            channel = r.channel,
        )
        return fit(body, maxBodyBytes)
    }

    /**
     * Only the V1 fields of [body] (`key`, `app` without its icon, `title`, `text`, `sub_text`,
     * `category`, `posted_at`, `redacted`): the fallback when the full body fails
     * [com.mtajchert.carom.app.core.protocol.MessageTypes.isValidBody], so a bad V2 field costs
     * the rich content, not the notification.
     */
    fun v1Only(body: MessageBodies.NotificationPosted): MessageBodies.NotificationPosted = MessageBodies.NotificationPosted(
        key = body.key,
        app = body.app.copy(icon = null),
        title = body.title,
        text = body.text,
        subText = body.subText,
        category = body.category,
        postedAt = body.postedAt.coerceAtLeast(0),
        redacted = body.redacted,
        resync = body.resync,
    )

    fun postedJson(body: MessageBodies.NotificationPosted): JsonObject =
        MessageBodies.toJson(MessageBodies.NotificationPosted.serializer(), body)

    fun removedJson(key: String): JsonObject =
        MessageBodies.toJson(MessageBodies.NotificationRemoved.serializer(), MessageBodies.NotificationRemoved(key))

    /**
     * SHA-256 (hex) of what the Mac would show: the body without `posted_at` (it changes on every
     * update) plus the digest of each image. Equal hashes mean the update can be dropped.
     */
    fun contentHash(body: MessageBodies.NotificationPosted, images: Map<ImageSlot, EncodedImage>): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(postedJson(body.copy(postedAt = 0)).toString().toByteArray(Charsets.UTF_8))
        for ((slot, image) in images.entries.sortedBy { it.key.toString() }) {
            md.update("|$slot=${image.digest}".toByteArray(Charsets.UTF_8))
        }
        return md.digest().toHex()
    }

    /**
     * The newest [RichContent.MAX_MESSAGES]. Image slots are indexes into the snapshot's list, so
     * refs are looked up before the older messages are cut.
     */
    private fun messages(
        list: List<SnapMessage>,
        refs: Map<ImageSlot, MessageBodies.BlobRef>,
    ): List<MessageBodies.ConversationMessage>? {
        val offset = (list.size - RichContent.MAX_MESSAGES).coerceAtLeast(0)
        return list.withIndex().drop(offset).map { (i, m) ->
            MessageBodies.ConversationMessage(
                sender = m.senderName?.let {
                    MessageBodies.Person(
                        name = truncate(it, MAX_NAME_CHARS),
                        key = m.senderKey?.let { k -> truncate(k, MAX_NAME_CHARS) },
                        icon = refs[ImageSlot.SenderIcon(i)],
                    )
                },
                text = truncate(m.text, MAX_LINE_CHARS),
                ts = m.ts,
                image = refs[ImageSlot.MessageImage(i)],
            )
        }.ifEmpty { null }
    }

    private fun progress(p: SnapProgress, refs: Map<ImageSlot, MessageBodies.BlobRef>) = MessageBodies.Progress(
        value = p.value,
        max = p.max,
        indeterminate = p.indeterminate,
        segments = p.segments.take(RichContent.MAX_SEGMENTS).ifEmpty { null },
        points = p.points.take(RichContent.MAX_POINTS).ifEmpty { null },
        trackerIcon = refs[ImageSlot.TrackerIcon],
        startIcon = refs[ImageSlot.StartIcon],
        endIcon = refs[ImageSlot.EndIcon],
    )

    private fun call(c: SnapCall, refs: Map<ImageSlot, MessageBodies.BlobRef>) = MessageBodies.Call(
        type = c.type,
        person = c.personName?.let { MessageBodies.Person(name = truncate(it, MAX_NAME_CHARS), icon = refs[ImageSlot.CallPersonIcon]) },
        isVideo = c.isVideo,
        verification = c.verification.clean(MAX_LINE_CHARS),
    )

    /**
     * The action title as the Mac receives it. `ActionExecutor` compares the live title
     * through this same cut, so a long title still matches its own button.
     */
    fun actionTitle(title: String): String = truncate(title, MAX_ACTION_TITLE_CHARS)

    private fun clip(a: MessageBodies.Action) = a.copy(
        title = actionTitle(a.title),
        reply = a.reply?.let { r ->
            r.copy(
                label = r.label?.let { truncate(it, MAX_CHOICE_CHARS) },
                choices = r.choices?.take(RichContent.MAX_CHOICES)?.map { truncate(it, MAX_CHOICE_CHARS) },
            )
        },
    )

    private fun size(body: MessageBodies.NotificationPosted): Int =
        postedJson(body).toString().toByteArray(Charsets.UTF_8).size

    /** Cuts [body] to [maxBytes]: oldest messages, then inbox lines from the end, then halves the longest text. */
    private fun fit(body: MessageBodies.NotificationPosted, maxBytes: Int): MessageBodies.NotificationPosted {
        var b = body
        while (size(b) > maxBytes && (b.messages?.size ?: 0) > 1) b = b.copy(messages = b.messages!!.drop(1))
        while (size(b) > maxBytes && !b.lines.isNullOrEmpty()) b = b.copy(lines = b.lines!!.dropLast(1).ifEmpty { null })
        repeat(MAX_SHRINK_ROUNDS) {
            if (size(b) <= maxBytes) return b
            b = shrinkLongest(b)
        }
        return b
    }

    private class Shrinkable(val length: Int, val shrink: () -> MessageBodies.NotificationPosted)

    private fun shrinkLongest(b: MessageBodies.NotificationPosted): MessageBodies.NotificationPosted {
        val last = b.messages?.lastOrNull()
        val candidates = listOf(
            Shrinkable(b.bigText?.length ?: 0) { b.copy(bigText = half(b.bigText)) },
            Shrinkable(b.text?.length ?: 0) { b.copy(text = half(b.text)) },
            Shrinkable(b.title?.length ?: 0) { b.copy(title = half(b.title)) },
            Shrinkable(b.subText?.length ?: 0) { b.copy(subText = half(b.subText)) },
            Shrinkable(b.summaryText?.length ?: 0) { b.copy(summaryText = half(b.summaryText)) },
            Shrinkable(last?.text?.length ?: 0) {
                b.copy(messages = b.messages!!.dropLast(1) + last!!.copy(text = half(last.text).orEmpty()))
            },
        )
        val longest = candidates.maxBy { it.length }
        return if (longest.length == 0) b else longest.shrink()
    }

    private fun half(s: String?): String? = s?.let { truncate(it, it.length / 2) }?.takeIf { it.isNotEmpty() }

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
