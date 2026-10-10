package com.mtajchert.carom.app.feature.clipboard.impl

import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Echo suppression (docs/CLIENTS.md "Clipboard"): remembers the SHA-256 of the last text received
 * from another device for 10 seconds, so the send paths do not bounce it straight back.
 * Only the hash is kept, never the text.
 */
@Singleton
class EchoGuard internal constructor(private val now: () -> Long) {
    @Inject constructor() : this(System::currentTimeMillis)

    private val lock = Any()
    private var hash: ByteArray? = null
    private var until = 0L

    fun remember(text: String) = synchronized(lock) {
        hash = sha256(text)
        until = now() + WINDOW_MS
    }

    fun isEcho(text: String): Boolean = synchronized(lock) {
        val h = hash ?: return false
        now() < until && MessageDigest.isEqual(h, sha256(text))
    }

    private fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))

    companion object {
        const val WINDOW_MS = 10_000L
    }
}
