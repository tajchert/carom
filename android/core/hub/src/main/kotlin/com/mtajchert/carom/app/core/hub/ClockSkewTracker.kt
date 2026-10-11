package com.mtajchert.carom.app.core.hub

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToLong

/** This device's clock is [skewMs] off the Hub's: `hub time - local time`, in Unix epoch ms. */
data class ClockDrift(val skewMs: Long) {
    /** True when the local clock is ahead of the Hub's. */
    val localAhead: Boolean get() = skewMs < 0

    /** The offset in whole minutes, never below 1. */
    val minutes: Long get() = maxOf(1L, (abs(skewMs) / 60_000.0).roundToLong())
}

/**
 * Compares the Hub's clock with this device's, from the `Date` header of Hub responses and the
 * `hub_time` of a clock-skew `400` (SPEC §6). Everything is Unix epoch ms / GMT, so the device's
 * time zone and locale never matter. [drift] turns non-null above [WARN_MS] and clears only
 * below [CLEAR_MS], so it does not flicker around the threshold.
 */
@Singleton
class ClockSkewTracker constructor(private val nowMs: () -> Long) {
    @Inject constructor() : this({ System.currentTimeMillis() })

    private val state = MutableStateFlow<ClockDrift?>(null)

    /** The current drift, or null when the clock is fine. */
    val drift: StateFlow<ClockDrift?> = state.asStateFlow()

    /** A response carrying this `Date` header just arrived. Missing or unparseable values are ignored. */
    fun onHubDate(header: String?) {
        val local = nowMs()
        val hub = parseHttpDate(header) ?: return
        update(hub - local)
    }

    /** The Hub told us its time (`hub_time`, Unix ms) in an error body that just arrived. */
    fun onHubTime(hubTimeMs: Long) {
        update(hubTimeMs - nowMs())
    }

    private fun update(skewMs: Long) {
        val magnitude = abs(skewMs)
        state.value = when {
            magnitude > WARN_MS -> ClockDrift(skewMs)
            magnitude < CLEAR_MS -> null
            else -> state.value?.let { ClockDrift(skewMs) }
        }
    }

    companion object {
        const val WARN_MS = 5 * 60_000L
        const val CLEAR_MS = 4 * 60_000L

        /** RFC 1123 / IMF-fixdate (`Sun, 11 Oct 2026 22:34:07 GMT`) to Unix ms; fixed English, no default locale or zone. */
        fun parseHttpDate(value: String?): Long? {
            if (value.isNullOrBlank()) return null
            return try {
                DateTimeFormatter.RFC_1123_DATE_TIME.parse(value.trim(), Instant::from).toEpochMilli()
            } catch (_: java.time.DateTimeException) {
                null
            }
        }
    }
}
