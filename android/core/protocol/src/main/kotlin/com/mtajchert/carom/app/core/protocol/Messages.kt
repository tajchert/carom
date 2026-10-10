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
    @Serializable
    data class App(val id: String, val name: String)

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
    ) {
        override fun toString() = "NotificationPosted(key=$key, app=${app.id})" // never log content
    }

    @Serializable
    data class NotificationRemoved(val key: String)

    /** Sent with payload-level `reply_to` = the posted message's msg_id. */
    @Serializable
    data class NotificationDismiss(val key: String)

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
