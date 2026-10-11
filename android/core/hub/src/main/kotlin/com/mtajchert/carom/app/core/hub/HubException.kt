package com.mtajchert.carom.app.core.hub

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.io.IOException

/** Hub API error codes (SPEC §6). [UNKNOWN] covers codes added by a newer Hub. */
enum class HubErrorCode(val code: String) {
    BAD_REQUEST("bad_request"),
    UNAUTHORIZED("unauthorized"),
    FORBIDDEN("forbidden"),
    NOT_FOUND("not_found"),
    CONFLICT("conflict"),
    GONE("gone"),
    TOO_LARGE("too_large"),
    RATE_LIMITED("rate_limited"),
    UNAVAILABLE("unavailable"),
    INTERNAL("internal"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromCode(code: String?): HubErrorCode = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/**
 * Every failure a [HubClient] call can raise. Callers branch on the subtype:
 *
 * - [Network]: no response (DNS, TLS, timeout, connection reset). Retry with backoff.
 * - [Http]: the Hub answered with an error. [Http.code] says what; 5xx is retryable.
 * - [MalformedResponse]: a 2xx body that does not match SPEC §6. Treat as a Hub bug.
 *
 * Messages never contain request bodies, tokens or message content.
 */
sealed class HubException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class Network(cause: IOException) : HubException("network error: ${cause.javaClass.simpleName}", cause)

    class Http(
        val status: Int,
        val code: HubErrorCode,
        /** The Hub's English message, for logs only; user-facing text comes from resources. */
        val hubMessage: String?,
        /** The whole error body, for route-specific fields. */
        val body: JsonObject?,
        /** `409 conflict` on `POST /v1/messages`: recipients that are no longer active (SPEC §5.2). */
        val inactive: List<String> = emptyList(),
        /** `429 rate_limited` on `POST /v1/messages`: recipients whose queue is full (SPEC §6 rule 6). */
        val full: List<String> = emptyList(),
    ) : HubException("hub error $status ${code.code}") {
        /** `429 rate_limited` on `PUT /v1/blobs/:id`: the Hub's blob storage is full (SPEC V2 §3.7). */
        val isQuotaExceeded: Boolean
            get() = status == 429 && (body?.get("quota") as? JsonPrimitive)?.booleanOrNull == true

        /** `400` on `POST /v1/messages` whose msg_id time is outside the Hub's window (`clock_skew: true`). */
        val isClockSkew: Boolean
            get() = status == 400 && (body?.get("clock_skew") as? JsonPrimitive)?.booleanOrNull == true

        /** The Hub's clock in Unix ms (`hub_time`) when it sent one, for the clock check. */
        val hubTime: Long?
            get() = (body?.get("hub_time") as? JsonPrimitive)?.longOrNull

        val isRetryable: Boolean
            get() = status >= 500 || (code == HubErrorCode.RATE_LIMITED && full.isEmpty() && !isQuotaExceeded)
    }

    class MalformedResponse(detail: String, cause: Throwable? = null) : HubException("malformed hub response: $detail", cause)
}
