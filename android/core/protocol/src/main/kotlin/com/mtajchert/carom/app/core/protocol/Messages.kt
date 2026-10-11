package com.mtajchert.carom.app.core.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** v1 message types (SPEC §4). */
object MessageTypes {
    const val NOTIFICATION_POSTED = "notification.posted@1"
    const val NOTIFICATION_REMOVED = "notification.removed@1"
    const val NOTIFICATION_DISMISS = "notification.dismiss@1"
    const val CLIPBOARD_SET = "clipboard.set@1"
    const val DEVICE_STATUS = "device.status@1"

    val ALL: Set<String> = linkedSetOf(
        NOTIFICATION_POSTED,
        NOTIFICATION_REMOVED,
        NOTIFICATION_DISMISS,
        CLIPBOARD_SET,
        DEVICE_STATUS,
    )

    private const val DAY_S = 86_400L

    /** Default TTL in seconds (SPEC §4 table). */
    fun defaultTtlS(type: String): Long = when (type) {
        NOTIFICATION_POSTED, NOTIFICATION_REMOVED -> 2 * DAY_S
        else -> DAY_S
    }

    /** The collapse real key (SPEC §3.2), or null when the type does not collapse. */
    fun collapseRealKey(type: String, body: JsonObject): String? = when (type) {
        NOTIFICATION_POSTED, NOTIFICATION_REMOVED -> body["key"].stringOrNull()?.let { "n:$it" }
        CLIPBOARD_SET -> "clip"
        DEVICE_STATUS -> "status"
        else -> null
    }

    /** Checks the known fields of a v1 body; unknown fields are allowed. False for unknown types. */
    fun isValidBody(type: String, body: JsonObject): Boolean {
        fun str(name: String) = body[name].stringOrNull() != null
        fun optStr(name: String) = name !in body || str(name)
        fun optBool(name: String) = name !in body || body[name].booleanOrNull() != null
        return when (type) {
            NOTIFICATION_POSTED -> {
                val app = body["app"].objectOrNull()
                str("key") && app != null && app["id"].stringOrNull() != null && app["name"].stringOrNull() != null &&
                    optStr("title") && optStr("text") && optStr("sub_text") && optStr("category") &&
                    body["posted_at"].safeUintOrNull() != null && body["redacted"].booleanOrNull() != null &&
                    optBool("ongoing")
            }
            NOTIFICATION_REMOVED, NOTIFICATION_DISMISS -> str("key")
            CLIPBOARD_SET ->
                body["mime"].stringOrNull() == "text/plain" && str("text") &&
                    body["sensitive"].booleanOrNull() != null && optStr("source")
            DEVICE_STATUS ->
                ("battery" !in body || body["battery"].integerOrNull()?.let { it in 0..100 } == true) &&
                    optBool("charging")
            else -> false
        }
    }
}

/** Typed v1 bodies (SPEC §4, protocol/schemas). Field order matches the reference encoder. */
object MessageBodies {
    /** An encrypted image on the Hub (SPEC V2 §3.1). */
    @Serializable
    data class BlobRef(
        val id: String,
        val key: String,
        val mime: String,
        val size: Long,
        val hash: String,
        val w: Int? = null,
        val h: Int? = null,
    ) {
        override fun toString() = "BlobRef(id=$id)" // the key must never reach a log
    }

    @Serializable
    data class App(val id: String, val name: String, val icon: BlobRef? = null)

    @Serializable
    data class Person(
        val name: String,
        val key: String? = null,
        val icon: BlobRef? = null,
    ) {
        override fun toString() = "Person(key=$key)" // never log names
    }

    @Serializable
    data class Conversation(
        val title: String? = null,
        @SerialName("is_group") val isGroup: Boolean,
        val icon: BlobRef? = null,
    ) {
        override fun toString() = "Conversation(isGroup=$isGroup)" // never log titles
    }

    @Serializable
    data class ConversationMessage(
        val sender: Person? = null,
        val text: String,
        val ts: Long,
        val image: BlobRef? = null,
    ) {
        override fun toString() = "ConversationMessage(ts=$ts)" // never log content
    }

    @Serializable
    data class ReplySpec(val label: String? = null, val choices: List<String>? = null) {
        override fun toString() = "ReplySpec(choices=${choices?.size ?: 0})" // never log content
    }

    @Serializable
    data class Action(
        val index: Int,
        val title: String,
        val semantic: Int? = null,
        val reply: ReplySpec? = null,
    ) {
        override fun toString() = "Action(index=$index)"
    }

    @Serializable
    data class Segment(val length: Int, val color: Int? = null)

    @Serializable
    data class Point(val position: Int, val color: Int? = null)

    @Serializable
    data class Progress(
        val value: Int,
        val max: Int,
        val indeterminate: Boolean,
        val segments: List<Segment>? = null,
        val points: List<Point>? = null,
        @SerialName("tracker_icon") val trackerIcon: BlobRef? = null,
        @SerialName("start_icon") val startIcon: BlobRef? = null,
        @SerialName("end_icon") val endIcon: BlobRef? = null,
    )

    @Serializable
    data class Live(
        val promoted: Boolean,
        @SerialName("short_critical_text") val shortCriticalText: String? = null,
    ) {
        override fun toString() = "Live(promoted=$promoted)" // never log content
    }

    @Serializable
    data class Chronometer(@SerialName("count_down") val countDown: Boolean)

    @Serializable
    data class Call(
        val type: String, // incoming|ongoing|screening|unknown
        val person: Person? = null,
        @SerialName("is_video") val isVideo: Boolean? = null,
        val verification: String? = null,
    ) {
        override fun toString() = "Call(type=$type)" // never log callers
    }

    @Serializable
    data class Group(
        val key: String,
        val summary: Boolean,
        @SerialName("sort_key") val sortKey: String? = null,
        val alert: String, // all|summary|children
    )

    @Serializable
    data class Channel(val id: String, val importance: Int)

    /** V1 fields first, in their V1 order, then the V2 additions in the order of SPEC V2 §3.3. */
    @Serializable
    data class NotificationPosted(
        val key: String,
        val app: App,
        val title: String? = null,
        val text: String? = null,
        @SerialName("sub_text") val subText: String? = null,
        val category: String? = null,
        @SerialName("posted_at") val postedAt: Long,
        val redacted: Boolean,
        val ongoing: Boolean? = null,
        val template: String? = null,
        val color: Int? = null,
        val colorized: Boolean? = null,
        @SerialName("small_icon") val smallIcon: BlobRef? = null,
        @SerialName("large_icon") val largeIcon: BlobRef? = null,
        val picture: BlobRef? = null,
        @SerialName("picture_desc") val pictureDesc: String? = null,
        @SerialName("big_text") val bigText: String? = null,
        val lines: List<String>? = null,
        @SerialName("summary_text") val summaryText: String? = null,
        val conversation: Conversation? = null,
        val messages: List<ConversationMessage>? = null,
        val actions: List<Action>? = null,
        @SerialName("can_open") val canOpen: Boolean? = null,
        val progress: Progress? = null,
        val live: Live? = null,
        @SerialName("when") val `when`: Long? = null,
        @SerialName("show_when") val showWhen: Boolean? = null,
        val chronometer: Chronometer? = null,
        val call: Call? = null,
        val group: Group? = null,
        val channel: Channel? = null,
    ) {
        override fun toString() = "NotificationPosted(key=$key, app=${app.id})" // never log content
    }

    @Serializable
    data class NotificationRemoved(val key: String)

    /** Sent with payload-level `reply_to` = the posted message's msg_id. */
    @Serializable
    data class NotificationDismiss(val key: String)

    /** What the Mac asks the phone to do with a notification (SPEC V2 §3.4). */
    sealed interface ActionRef {
        data object Tap : ActionRef
        data object Answer : ActionRef
        data object Decline : ActionRef

        data class Button(val index: Int, val title: String, val replyText: String? = null) : ActionRef {
            override fun toString() = "Button(index=$index)" // the reply text is what the user typed
        }
    }

    /** Sent with payload-level `reply_to` = the posted message's msg_id. */
    @Serializable
    data class NotificationAction(
        val key: String,
        @Serializable(with = ActionRefSerializer::class) val action: ActionRef,
    ) {
        override fun toString() = "NotificationAction(key=$key)"
    }

    /** Sent with payload-level `reply_to` = the action's msg_id. [error] is gone|changed|blocked|failed. */
    @Serializable
    data class NotificationActionResult(val ok: Boolean, val error: String? = null)

    /** [state] is playing|paused|buffering|stopped; [controls] lists play|pause|next|previous|seek. */
    @Serializable
    data class MediaState(
        val session: String,
        val app: App,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val art: BlobRef? = null,
        val state: String,
        @SerialName("position_ms") val positionMs: Long? = null,
        @SerialName("position_at") val positionAt: Long? = null,
        @SerialName("duration_ms") val durationMs: Long? = null,
        val controls: List<String>,
    ) {
        override fun toString() = "MediaState(session=$session, state=$state)" // never log titles
    }

    /** Sent with payload-level `reply_to` = a `media.state` msg_id of the same session. [command] is play|pause|next|previous|seek. */
    @Serializable
    data class MediaCommand(
        val session: String,
        val command: String,
        @SerialName("position_ms") val positionMs: Long? = null,
    )

    @Serializable
    data class ClipboardSet(
        val mime: String = "text/plain",
        val text: String,
        val sensitive: Boolean,
        val source: String? = null,
    ) {
        override fun toString() = "ClipboardSet(length=${text.length}, sensitive=$sensitive)" // never log content
    }

    @Serializable
    data class DeviceStatus(val battery: Int? = null, val charging: Boolean? = null)

    fun <T> toJson(serializer: KSerializer<T>, body: T): JsonObject =
        ProtocolJson.encodeToJsonElement(serializer, body).jsonObject

    /** Decodes a body that already passed [MessageTypes.isValidBody]; null if it does not fit. */
    fun <T> fromJson(serializer: KSerializer<T>, body: JsonObject): T? = try {
        ProtocolJson.decodeFromJsonElement(serializer, body)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
