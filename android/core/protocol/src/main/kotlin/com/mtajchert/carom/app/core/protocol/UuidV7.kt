package com.mtajchert.carom.app.core.protocol

/**
 * Monotonic UUIDv7 msg_ids (SPEC §3.1), RFC 9562 §6.2 method 1: a 12-bit counter in rand_a,
 * reseeded with its top bit clear on every new millisecond. When the clock goes backwards the
 * last timestamp is reused; on counter overflow the timestamp is advanced by 1 ms.
 *
 * This mirrors the TypeScript reference generator, so with the vectors' seeded rng it
 * reproduces `uuidv7.json` `generator_sequence` exactly.
 */
object UuidV7 {
    private val SHAPE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    private const val MAX_MS = (1L shl 48) - 1
    private const val COUNTER_MAX = 0xfff

    /** Lowercase, hyphenated UUID with version 7 and the RFC 9562 variant. */
    fun isValid(s: String?): Boolean = s != null && SHAPE.matches(s)

    /** The 48-bit Unix millisecond timestamp of a UUIDv7. */
    fun timestamp(id: String): Long {
        require(isValid(id)) { "not a UUIDv7" }
        return (id.substring(0, 8) + id.substring(9, 13)).toLong(16)
    }

    /** Formats a UUIDv7 from its parts; [randB] is 8 bytes of which the low 62 bits are used. */
    fun format(ms: Long, counter: Int, randB: ByteArray): String {
        require(ms in 0..MAX_MS) { "timestamp out of range" }
        require(counter in 0..COUNTER_MAX) { "counter out of range" }
        require(randB.size == 8) { "randB must be 8 bytes" }
        val bytes = ByteArray(16)
        var t = ms
        for (i in 5 downTo 0) {
            bytes[i] = (t and 0xff).toByte()
            t = t ushr 8
        }
        bytes[6] = (0x70 or (counter shr 8)).toByte()
        bytes[7] = (counter and 0xff).toByte()
        randB.copyInto(bytes, 8)
        bytes[8] = (0x80 or (bytes[8].toInt() and 0x3f)).toByte()
        val h = bytes.toHex()
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }
}

/** Generates strictly increasing msg_ids. Thread-safe. */
class UuidV7Generator(
    private val clock: () -> Long = System::currentTimeMillis,
    private val rng: Rng = SecureRng,
) {
    private var lastMs = -1L
    private var counter = 0

    /** Restores monotonicity across process restarts: ids are never generated at or below [ms]. */
    @Synchronized
    fun observe(ms: Long) {
        if (ms > lastMs) {
            lastMs = ms
            counter = 0xfff
        }
    }

    @Synchronized
    fun next(): String {
        val r = rng.bytes(10)
        val seed = (((r[0].toInt() and 0xff) shl 8) or (r[1].toInt() and 0xff)) and 0x7ff
        var ms = clock()
        if (ms > lastMs) {
            counter = seed
        } else {
            ms = lastMs
            counter++
            if (counter > 0xfff) {
                ms = lastMs + 1
                counter = seed
            }
        }
        lastMs = ms
        return UuidV7.format(ms, counter, r.copyOfRange(2, 10))
    }
}
