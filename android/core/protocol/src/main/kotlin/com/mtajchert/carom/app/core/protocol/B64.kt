package com.mtajchert.carom.app.core.protocol

/**
 * base64url without padding (RFC 4648 §5), SPEC §1.
 *
 * Decoding is strict: no padding, no characters outside the alphabet (so no `+`, `/` or
 * whitespace), no length with `len % 4 == 1`, and canonical (zero) unused trailing bits.
 * `java.util.Base64` is deliberately not used for decoding because it accepts
 * non-canonical trailing bits.
 */
object B64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val LOOKUP = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { i, c -> table[c.code] = i }
    }

    fun encode(data: ByteArray): String {
        val out = StringBuilder((data.size * 4 + 2) / 3)
        var i = 0
        while (i + 3 <= data.size) {
            val n = (data[i].u() shl 16) or (data[i + 1].u() shl 8) or data[i + 2].u()
            out.append(ALPHABET[(n shr 18) and 63]).append(ALPHABET[(n shr 12) and 63])
                .append(ALPHABET[(n shr 6) and 63]).append(ALPHABET[n and 63])
            i += 3
        }
        when (data.size - i) {
            1 -> {
                val n = data[i].u() shl 16
                out.append(ALPHABET[(n shr 18) and 63]).append(ALPHABET[(n shr 12) and 63])
            }
            2 -> {
                val n = (data[i].u() shl 16) or (data[i + 1].u() shl 8)
                out.append(ALPHABET[(n shr 18) and 63]).append(ALPHABET[(n shr 12) and 63])
                    .append(ALPHABET[(n shr 6) and 63])
            }
        }
        return out.toString()
    }

    /** Strict decode; null on any non-canonical input. */
    fun decodeOrNull(s: String): ByteArray? {
        if (s.length % 4 == 1) return null
        val fullGroups = s.length / 4
        val rest = s.length % 4
        val out = ByteArray(fullGroups * 3 + if (rest == 0) 0 else rest - 1)
        var o = 0
        var i = 0
        repeat(fullGroups) {
            val a = sextet(s, i) ?: return null
            val b = sextet(s, i + 1) ?: return null
            val c = sextet(s, i + 2) ?: return null
            val d = sextet(s, i + 3) ?: return null
            val n = (a shl 18) or (b shl 12) or (c shl 6) or d
            out[o++] = (n shr 16).toByte()
            out[o++] = (n shr 8).toByte()
            out[o++] = n.toByte()
            i += 4
        }
        when (rest) {
            2 -> {
                val a = sextet(s, i) ?: return null
                val b = sextet(s, i + 1) ?: return null
                if (b and 0x0f != 0) return null
                out[o] = ((a shl 2) or (b shr 4)).toByte()
            }
            3 -> {
                val a = sextet(s, i) ?: return null
                val b = sextet(s, i + 1) ?: return null
                val c = sextet(s, i + 2) ?: return null
                if (c and 0x03 != 0) return null
                out[o++] = ((a shl 2) or (b shr 4)).toByte()
                out[o] = (((b and 0x0f) shl 4) or (c shr 2)).toByte()
            }
        }
        return out
    }

    /** Strict decode; throws [IllegalArgumentException] on non-canonical input. */
    fun decode(s: String): ByteArray = requireNotNull(decodeOrNull(s)) { "invalid base64url" }

    /** Strict decode that also checks the byte length; null otherwise. */
    fun decodeLength(s: String?, length: Int): ByteArray? =
        s?.let(::decodeOrNull)?.takeIf { it.size == length }

    private fun sextet(s: String, i: Int): Int? {
        val c = s[i].code
        val v = if (c < 128) LOOKUP[c] else -1
        return if (v < 0) null else v
    }

    private fun Byte.u(): Int = toInt() and 0xff
}

fun ByteArray.toB64(): String = B64.encode(this)
