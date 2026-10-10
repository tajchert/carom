package com.mtajchert.carom.app.core.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The JSON configuration for everything on the wire.
 *
 * - Unknown fields are ignored when decoding (SPEC §4: receivers MUST ignore unknown fields).
 * - Optional (null) fields are omitted when encoding, and defaults are written.
 *
 * Untrusted input is never decoded straight into data classes: it is parsed into a
 * [JsonElement] and checked with the strict accessors below, so number and type rules match
 * the TypeScript reference (for example `ttl_s: 86400.5` or `v: "1"` are rejected).
 */
val ProtocolJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/** Parses JSON text, returning null on any syntax error. */
fun parseJsonOrNull(text: String): JsonElement? = try {
    ProtocolJson.parseToJsonElement(text)
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

/** Strict UTF-8 decode, then [parseJsonOrNull]. */
fun parseJsonOrNull(bytes: ByteArray): JsonElement? = bytes.decodeUtf8OrNull()?.let(::parseJsonOrNull)

internal fun JsonElement?.objectOrNull(): JsonObject? = this as? JsonObject

internal fun JsonElement?.arrayOrNull(): JsonArray? = this as? JsonArray

/** A JSON string (not a number or literal that merely looks like one). */
internal fun JsonElement?.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonElement?.booleanOrNull(): Boolean? {
    val p = this as? JsonPrimitive ?: return null
    if (p.isString || p is JsonNull) return null
    return when (p.content) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

/**
 * A JSON number with an integral value that fits a Long, or null. Accepts the same values
 * JavaScript's `Number.isInteger` accepts for JSON input (so `1.0` is 1, `1.5` is rejected).
 */
internal fun JsonElement?.integerOrNull(): Long? {
    val p = this as? JsonPrimitive ?: return null
    if (p.isString || p is JsonNull) return null
    val content = p.content
    if (content == "true" || content == "false") return null
    val decimal = content.toBigDecimalOrNull() ?: return null
    return try {
        decimal.stripTrailingZeros().takeIf { it.scale() <= 0 }?.longValueExact()
    } catch (_: ArithmeticException) {
        null
    }
}

/** A non-negative integer no larger than `Number.MAX_SAFE_INTEGER` (JavaScript `Number.isSafeInteger`). */
internal fun JsonElement?.safeUintOrNull(): Long? =
    integerOrNull()?.takeIf { it in 0..Protocol.Limits.MAX_SAFE_INTEGER }

internal fun JsonElement?.isJsonNumber(): Boolean {
    val p = this as? JsonPrimitive ?: return false
    return !p.isString && p !is JsonNull && p.content.toBigDecimalOrNull() != null
}
