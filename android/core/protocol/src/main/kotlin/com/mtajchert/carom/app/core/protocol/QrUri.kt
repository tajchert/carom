package com.mtajchert.carom.app.core.protocol

import java.net.URI
import java.net.URISyntaxException

/**
 * A parsed QR / pasted-text URI (SPEC §8):
 * ```
 * carom://setup?v=1&h=<urlenc hub url>&t=<setup token>
 * carom://pair?v=1&h=<urlenc hub url>&c=<pair_id>&s=<b64 pair_secret>
 * ```
 * [toString] never prints the setup token or pair secret: the QR code is the trust channel
 * and must never be logged.
 */
sealed interface QrPayload {
    val hub: String

    class Setup(override val hub: String, val setupToken: String) : QrPayload {
        override fun toString(): String = "QrPayload.Setup(hub=$hub)"
    }

    class Pair(override val hub: String, val pairId: String, val pairSecret: ByteArray) : QrPayload {
        override fun toString(): String = "QrPayload.Pair(hub=$hub, pairId=$pairId)"
    }
}

object QrUri {
    private const val MIN_SETUP_TOKEN = 32
    private const val UPPER_HEX = "0123456789ABCDEF"
    private val PAIR_ID = Regex("^[A-Za-z0-9_-]{22}$")
    private val RAW_VALUE = Regex("^(?:[A-Za-z0-9\\-._~!$'()*,;:@/?=]|%[0-9A-Fa-f]{2})*$")
    private val CONTROL = Regex("[\\u0000-\\u001f\\u007f]")
    private val WHITESPACE = Regex("\\s")
    private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")
    private val URI_SHAPE = Regex("^carom://(setup|pair)\\?(.*)$", RegexOption.DOT_MATCHES_ALL)
    private val PARAMS = mapOf("setup" to listOf("v", "h", "t"), "pair" to listOf("v", "h", "c", "s"))

    private fun isUnreserved(b: Int): Boolean =
        b in 0x30..0x39 || b in 0x41..0x5a || b in 0x61..0x7a || b == 0x2d || b == 0x2e || b == 0x5f || b == 0x7e

    /** Percent-encodes every UTF-8 byte except `A-Z a-z 0-9 - . _ ~`, with uppercase hex. */
    fun percentEncode(s: String): String = buildString {
        for (byte in s.utf8()) {
            val b = byte.toInt() and 0xff
            if (isUnreserved(b)) {
                append(b.toChar())
            } else {
                append('%').append(UPPER_HEX[b shr 4]).append(UPPER_HEX[b and 0xf])
            }
        }
    }

    private fun percentDecode(raw: String): String? {
        if (!RAW_VALUE.matches(raw)) return null
        val bytes = java.io.ByteArrayOutputStream(raw.length)
        var i = 0
        while (i < raw.length) {
            if (raw[i] == '%') {
                bytes.write(raw.substring(i + 1, i + 3).toInt(16))
                i += 3
            } else {
                bytes.write(raw[i].code)
                i++
            }
        }
        return bytes.toByteArray().decodeUtf8OrNull()
    }

    /** Hub base URL: https (http only for loopback hosts), no credentials, query or fragment. */
    fun isValidHubUrl(s: String): Boolean {
        if (CONTROL.containsMatchIn(s) || WHITESPACE.containsMatchIn(s) || '?' in s || '#' in s) return false
        val uri = try {
            URI(s)
        } catch (_: URISyntaxException) {
            return false
        }
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        if (uri.rawUserInfo != null || uri.port !in -1..65535) return false
        return scheme == "https" || (scheme == "http" && host in LOOPBACK_HOSTS)
    }

    fun isValidSetupToken(s: String): Boolean = s.length >= MIN_SETUP_TOKEN && !CONTROL.containsMatchIn(s)

    fun isValidPairId(s: String): Boolean = PAIR_ID.matches(s)

    fun buildSetup(hub: String, setupToken: String): String {
        require(isValidHubUrl(hub)) { "invalid hub url" }
        require(isValidSetupToken(setupToken)) { "invalid setup token" }
        return "carom://setup?v=1&h=${percentEncode(hub)}&t=${percentEncode(setupToken)}"
    }

    fun buildPair(hub: String, pairId: String, pairSecret: ByteArray): String {
        require(isValidHubUrl(hub)) { "invalid hub url" }
        require(isValidPairId(pairId)) { "invalid pair id" }
        require(pairSecret.size == 32) { "pair secret must be 32 bytes" }
        return "carom://pair?v=1&h=${percentEncode(hub)}&c=$pairId&s=${pairSecret.toB64()}"
    }

    private fun parseQuery(query: String, allowed: List<String>): Map<String, String>? {
        val params = LinkedHashMap<String, String>()
        for (part in query.split('&')) {
            val eq = part.indexOf('=')
            if (eq <= 0) return null
            val key = part.substring(0, eq)
            val value = percentDecode(part.substring(eq + 1)) ?: return null
            if (key !in allowed || key in params) return null
            params[key] = value
        }
        return if (params.size == allowed.size) params else null
    }

    /** Parses pasted or scanned text (surrounding whitespace is ignored). Null when invalid. */
    fun parse(text: String): QrPayload? {
        val match = URI_SHAPE.matchEntire(text.trim()) ?: return null
        val kind = match.groupValues[1]
        val params = parseQuery(match.groupValues[2], PARAMS.getValue(kind)) ?: return null
        if (params["v"] != "1") return null
        val hub = params.getValue("h")
        if (!isValidHubUrl(hub)) return null
        if (kind == "setup") {
            val token = params.getValue("t")
            return if (isValidSetupToken(token)) QrPayload.Setup(hub, token) else null
        }
        val pairId = params.getValue("c")
        val secret = B64.decodeLength(params["s"], 32)
        return if (isValidPairId(pairId) && secret != null) QrPayload.Pair(hub, pairId, secret) else null
    }
}
