package com.mtajchert.carom.app.feature.notifications.impl

/**
 * "A notification update re-sends `posted` with the same collapse key, at most once per 1.5 s per
 * key", with these exceptions and additions (V2 spec §5.1):
 * - an update whose content hash equals the last sent one is a duplicate ([isDuplicate]);
 * - a progress bar that just completed, or a call whose type changed, is not delayed ([Traits]).
 *
 * Remembers what each key last sent and when; not thread-safe, the forwarder's per-key workers
 * call it under their own coordination.
 */
class UpdateThrottle(private val windowMs: Long = WINDOW_MS, private val maxKeys: Int = MAX_KEYS) {

    /** The parts of an update that decide whether it skips the window. */
    data class Traits(val progressComplete: Boolean = false, val callType: String? = null)

    private class Entry(val sentAt: Long, val hash: String?, val traits: Traits)

    private val last = LinkedHashMap<String, Entry>()

    /** How long to wait before [key] may be sent again; 0 means now. */
    fun delayFor(key: String, now: Long, traits: Traits = Traits()): Long {
        val entry = last[key] ?: return 0
        if (immediate(entry.traits, traits)) return 0
        return (entry.sentAt + windowMs - now).coerceIn(0, windowMs)
    }

    /** True when [hash] is the content hash of the last update sent for [key]. */
    fun isDuplicate(key: String, hash: String): Boolean = last[key]?.hash == hash

    fun markSent(key: String, now: Long, hash: String? = null, traits: Traits = Traits()) {
        last.remove(key)
        last[key] = Entry(now, hash, traits)
        // Entries older than the window no longer throttle anything; keep the map small.
        if (last.size > maxKeys) {
            val it = last.entries.iterator()
            while (it.hasNext() && last.size > maxKeys / 2) {
                val e = it.next()
                if (now - e.value.sentAt >= windowMs || last.size > maxKeys) it.remove() else break
            }
        }
    }

    /** The send did not go out: an identical update must be sent next time, but the rate limit stays. */
    fun forgetContent(key: String) {
        val entry = last[key] ?: return
        last[key] = Entry(entry.sentAt, null, entry.traits)
    }

    fun forget(key: String) {
        last.remove(key)
    }

    private fun immediate(previous: Traits, next: Traits): Boolean =
        (next.progressComplete && !previous.progressComplete) || next.callType != previous.callType

    companion object {
        const val WINDOW_MS = 1_500L
        private const val MAX_KEYS = 256
    }
}
