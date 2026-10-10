package com.mtajchert.carom.app.core.protocol

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

// Byte helpers and the SPEC §1 encodings lp() and u64().

/** UTF-8 bytes of a string. */
fun String.utf8(): ByteArray = toByteArray(Charsets.UTF_8)

/** Strict UTF-8 decode: returns null on malformed input instead of substituting U+FFFD. */
fun ByteArray.decodeUtf8OrNull(): String? = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(this))
        .toString()
} catch (_: CharacterCodingException) {
    null
}

fun concat(vararg parts: ByteArray): ByteArray {
    val out = ByteArray(parts.sumOf { it.size })
    var offset = 0
    for (part in parts) {
        part.copyInto(out, offset)
        offset += part.size
    }
    return out
}

fun concat(parts: List<ByteArray>): ByteArray = concat(*parts.toTypedArray())

/** 4-byte big-endian unsigned integer. */
fun u32be(n: Long): ByteArray {
    require(n in 0..0xffff_ffffL) { "u32 out of range: $n" }
    return byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
}

/** `u64(n)`: 8-byte big-endian unsigned integer. Negative values are rejected. */
fun u64(n: Long): ByteArray {
    require(n >= 0) { "u64 out of range: $n" }
    return ByteBuffer.allocate(8).putLong(n).array()
}

/** `u64(n)` for the full unsigned range. */
fun u64(n: ULong): ByteArray = ByteBuffer.allocate(8).putLong(n.toLong()).array()

/** `lp(x) = u32be(len(x)) || x`. */
fun lp(x: ByteArray): ByteArray = concat(u32be(x.size.toLong()), x)

/** `lp(x)` for strings, which are UTF-8 encoded first. */
fun lp(x: String): ByteArray = lp(x.utf8())

/** Constant-time equality; false on a length mismatch. */
fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b) && a.size == b.size

fun ByteArray.isAllZero(): Boolean {
    var acc = 0
    for (b in this) acc = acc or b.toInt()
    return acc == 0
}

private const val HEX = "0123456789abcdef"

/** Lowercase hex. */
fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (b in this) {
        out.append(HEX[(b.toInt() shr 4) and 0xf])
        out.append(HEX[b.toInt() and 0xf])
    }
    return out.toString()
}

/** Parses lowercase hex; throws [IllegalArgumentException] on anything else. */
fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0 && all { it in '0'..'9' || it in 'a'..'f' }) { "invalid hex" }
    return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
