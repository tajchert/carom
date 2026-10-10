package com.mtajchert.carom.app.feature.notifications.impl

/**
 * "A notification update re-sends `posted` with the same collapse key, at most once per 2 s per
 * key" (docs/CLIENTS.md). Remembers when each key was last sent; not thread-safe, the forwarder
 * calls it from its single consumer.
 */
class UpdateThrottle(private val windowMs: Long = WINDOW_MS, private val maxKeys: Int = MAX_KEYS) {
    private val lastSent = LinkedHashMap<String, Long>()

    /** How long to wait before [key] may be sent again; 0 means now. */
    fun delayFor(key: String, now: Long): Long {
        val last = lastSent[key] ?: return 0
        return (last + windowMs - now).coerceIn(0, windowMs)
    }

    fun markSent(key: String, now: Long) {
        lastSent.remove(key)
        lastSent[key] = now
        // Entries older than the window no longer throttle anything; keep the map small.
        if (lastSent.size > maxKeys) {
            val it = lastSent.entries.iterator()
            while (it.hasNext() && lastSent.size > maxKeys / 2) {
                val e = it.next()
                if (now - e.value >= windowMs || lastSent.size > maxKeys) it.remove() else break
            }
        }
    }

    fun forget(key: String) {
        lastSent.remove(key)
    }

    companion object {
        const val WINDOW_MS = 2_000L
        private const val MAX_KEYS = 256
    }
}
