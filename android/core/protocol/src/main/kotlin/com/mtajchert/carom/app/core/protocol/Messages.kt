package com.mtajchert.carom.app.core.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Message types (SPEC §4, and SPEC V2 §3.4 for the last four). */
object MessageTypes {
    const val NOTIFICATION_POSTED = "notification.posted@1"
    const val NOTIFICATION_REMOVED = "notification.removed@1"
    const val NOTIFICATION_DISMISS = "notification.dismiss@1"
    const val CLIPBOARD_SET = "clipboard.set@1"
    const val DEVICE_STATUS = "device.status@1"
    const val NOTIFICATION_ACTION = "notification.action@1"
    const val NOTIFICATION_ACTION_RESULT = "notification.action_result@1"
    const val MEDIA_STATE = "media.state@1"
    const val MEDIA_COMMAND = "media.command@1"

    val ALL: Set<String> = linkedSetOf(
        NOTIFICATION_POSTED,
        NOTIFICATION_REMOVED,
        NOTIFICATION_DISMISS,
        CLIPBOARD_SET,
        DEVICE_STATUS,
        NOTIFICATION_ACTION,
        NOTIFICATION_ACTION_RESULT,
        MEDIA_STATE,
        MEDIA_COMMAND,
    )

    /** Types that are always a reply: a message without payload-level `reply_to` is `bad_payload` (SPEC V2 §3.5). */
    val REQUIRES_REPLY_TO: Set<String> = setOf(
        NOTIFICATION_DISMISS,
        NOTIFICATION_ACTION,
        NOTIFICATION_ACTION_RESULT,
        MEDIA_COMMAND,
    )

    private const val DAY_S = 86_400L
    private const val ACTION_TTL_S = 600L

    private val MEDIA_COMMANDS = setOf("play", "pause", "next", "previous", "seek")

    /** Default TTL in seconds (SPEC §4 table, SPEC V2 §3.4). */
    fun defaultTtlS(type: String): Long = when (type) {
        NOTIFICATION_POSTED, NOTIFICATION_REMOVED -> 2 * DAY_S
        NOTIFICATION_ACTION, NOTIFICATION_ACTION_RESULT, MEDIA_COMMAND -> ACTION_TTL_S
        else -> DAY_S
    }

    /** The collapse real key (SPEC §3.2), or null when the type does not collapse. */
    fun collapseRealKey(type: String, body: JsonObject): String? = when (type) {
        NOTIFICATION_POSTED, NOTIFICATION_REMOVED -> body["key"].stringOrNull()?.let { "n:$it" }
        CLIPBOARD_SET -> "clip"
        DEVICE_STATUS -> "status"
        MEDIA_STATE -> body["session"].stringOrNull()?.let { "m:$it" }
        else -> null
    }

    /** Decodes with [ActionRefSerializer], so the validator and the typed decoder share one rule. */
    private fun isValidActionRef(element: JsonElement?): Boolean {
        if (element == null) return false
        return try {
            ProtocolJson.decodeFromJsonElement(ActionRefSerializer, element)
            true
        } catch (_: SerializationException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private const val MAX_LINES = 7
    private const val MAX_MESSAGES = 6
    private const val MAX_ACTIONS = 6
    private const val MAX_SEGMENTS = 10
    private const val MAX_POINTS = 4
    private const val BLOB_ID_LENGTH = 32
    private const val BLOB_HASH_LENGTH = 16

    private fun JsonObject.has(name: String) = name in this
    private fun JsonObject.str(name: String) = this[name].stringOrNull() != null
    private fun JsonObject.optStr(name: String) = !has(name) || str(name)
    private fun JsonObject.optBool(name: String) = !has(name) || this[name].booleanOrNull() != null
    private fun JsonObject.optUint(name: String) = !has(name) || this[name].safeUintOrNull() != null
    private fun JsonObject.int32(name: String) = this[name].integerOrNull()?.let { it in Int.MIN_VALUE..Int.MAX_VALUE } == true
    private fun JsonObject.optInt32(name: String) = !has(name) || int32(name)
    private fun JsonObject.uint31(name: String) = this[name].integerOrNull()?.let { it in 0..Int.MAX_VALUE.toLong() } == true
    private fun JsonObject.optUint31(name: String) = !has(name) || uint31(name)

    /** An image dimension: an int32 of at least 1. */
    private fun JsonObject.optDimension(name: String) =
        !has(name) || this[name].integerOrNull()?.let { it in 1..Int.MAX_VALUE.toLong() } == true

    /**
     * A blob reference (SPEC V2 §3.1): 32-byte id and key, 16-byte hash, all unpadded base64url; a
     * non-empty mime, a size of at most [Protocol.Limits.MAX_BLOB_PLAINTEXT], and dimensions of at least 1.
     */
    private fun isValidBlob(element: JsonElement?): Boolean {
        val o = element.objectOrNull() ?: return false
        return B64.decodeLength(o["id"].stringOrNull(), BLOB_ID_LENGTH) != null &&
            B64.decodeLength(o["key"].stringOrNull(), Protocol.KEY_LENGTH) != null &&
            B64.decodeLength(o["hash"].stringOrNull(), BLOB_HASH_LENGTH) != null &&
            o["mime"].stringOrNull()?.isNotEmpty() == true &&
            o["size"].safeUintOrNull()?.let { it <= Protocol.Limits.MAX_BLOB_PLAINTEXT } == true &&
            o.optDimension("w") && o.optDimension("h")
    }

    private fun JsonObject.optBlob(name: String) = !has(name) || isValidBlob(this[name])

    private fun isValidApp(element: JsonElement?): Boolean {
        val o = element.objectOrNull() ?: return false
        return o.str("id") && o.str("name") && o.optBlob("icon")
    }

    private fun isValidPerson(element: JsonElement?): Boolean {
        val o = element.objectOrNull() ?: return false
        return o.str("name") && o.optStr("key") && o.optBlob("icon")
    }

    /** True when [name] is absent, or an array of at most [max] elements that all satisfy [check]. */
    private fun JsonObject.optList(name: String, max: Int, check: (JsonElement) -> Boolean): Boolean {
        if (!has(name)) return true
        val array = this[name].arrayOrNull() ?: return false
        return array.size <= max && array.all(check)
    }

    private fun JsonObject.optSub(name: String, check: (JsonObject) -> Boolean): Boolean {
        if (!has(name)) return true
        val o = this[name].objectOrNull() ?: return false
        return check(o)
    }

    private fun isValidPostedExtras(body: JsonObject): Boolean =
        body.optStr("template") && body.optInt32("color") && body.optBool("colorized") &&
            body.optBlob("small_icon") && body.optBlob("large_icon") && body.optBlob("picture") &&
            body.optStr("picture_desc") && body.optStr("big_text") &&
            body.optList("lines", MAX_LINES) { it.stringOrNull() != null } &&
            body.optStr("summary_text") &&
            body.optSub("conversation") { it.optStr("title") && it["is_group"].booleanOrNull() != null && it.optBlob("icon") } &&
            body.optList("messages", MAX_MESSAGES) { m ->
                val o = m.objectOrNull()
                o != null && (!o.has("sender") || isValidPerson(o["sender"])) && o.str("text") &&
                    o["ts"].safeUintOrNull() != null && o.optBlob("image")
            } &&
            body.optList("actions", MAX_ACTIONS) { a ->
                val o = a.objectOrNull()
                o != null && o.uint31("index") && o.str("title") && o.optUint31("semantic") &&
                    o.optSub("reply") { r -> r.optStr("label") && r.optList("choices", Int.MAX_VALUE) { c -> c.stringOrNull() != null } }
            } &&
            body.optSub("progress") { p ->
                p.uint31("value") && p.uint31("max") && p["indeterminate"].booleanOrNull() != null &&
                    p.optList("segments", MAX_SEGMENTS) { s ->
                        val o = s.objectOrNull()
                        o != null && o.uint31("length") && o.optInt32("color")
                    } &&
                    p.optList("points", MAX_POINTS) { pt ->
                        val o = pt.objectOrNull()
                        o != null && o.uint31("position") && o.optInt32("color")
                    } &&
                    p.optBlob("tracker_icon") && p.optBlob("start_icon") && p.optBlob("end_icon")
            } &&
            body.optSub("live") { it["promoted"].booleanOrNull() != null && it.optStr("short_critical_text") } &&
            body.optUint("when") && body.optBool("show_when") &&
            body.optSub("chronometer") { it["count_down"].booleanOrNull() != null } &&
            body.optSub("call") { c ->
                c.str("type") && (!c.has("person") || isValidPerson(c["person"])) && c.optBool("is_video") && c.optStr("verification")
            } &&
            body.optSub("group") { g -> g.str("key") && g["summary"].booleanOrNull() != null && g.optStr("sort_key") && g.str("alert") } &&
            body.optSub("channel") { it.str("id") && it.int32("importance") }

    /**
     * Checks a body; unknown fields are allowed. False for unknown types. The V2 nested objects are
     * checked against messages.json: a malformed one makes the whole body `bad_payload`, so the
     * typed bodies decode whatever this accepts.
     */
    fun isValidBody(type: String, body: JsonObject): Boolean = when (type) {
        NOTIFICATION_POSTED ->
            body.str("key") && isValidApp(body["app"]) &&
                body.optStr("title") && body.optStr("text") && body.optStr("sub_text") && body.optStr("category") &&
                body["posted_at"].safeUintOrNull() != null && body["redacted"].booleanOrNull() != null &&
                body.optBool("ongoing") && isValidPostedExtras(body)
        NOTIFICATION_REMOVED, NOTIFICATION_DISMISS -> body.str("key")
        NOTIFICATION_ACTION -> body.str("key") && isValidActionRef(body["action"])
        NOTIFICATION_ACTION_RESULT -> body["ok"].booleanOrNull() != null && body.optStr("error")
        MEDIA_STATE ->
            body["session"].stringOrNull()?.isNotEmpty() == true && isValidApp(body["app"]) &&
                body.optStr("title") && body.optStr("artist") && body.optStr("album") && body.optBlob("art") && body.str("state") &&
                body.optUint("position_ms") && body.optUint("position_at") && body.optUint("duration_ms") &&
                body["controls"].arrayOrNull()?.all { it.stringOrNull() != null } == true
        MEDIA_COMMAND -> {
            val command = body["command"].stringOrNull()
            body["session"].stringOrNull()?.isNotEmpty() == true && command != null && command in MEDIA_COMMANDS &&
                body.optUint("position_ms") && (command != "seek" || body["position_ms"].safeUintOrNull() != null)
        }
        CLIPBOARD_SET ->
            body["mime"].stringOrNull() == "text/plain" && body.str("text") &&
                body["sensitive"].booleanOrNull() != null && body.optStr("source")
        DEVICE_STATUS ->
            (!body.has("battery") || body["battery"].integerOrNull()?.let { it in 0..100 } == true) &&
                body.optBool("charging")
        else -> false
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
