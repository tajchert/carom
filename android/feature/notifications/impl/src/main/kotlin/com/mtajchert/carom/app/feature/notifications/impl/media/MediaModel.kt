package com.mtajchert.carom.app.feature.notifications.impl.media

import android.service.notification.StatusBarNotification
import com.mtajchert.carom.app.core.protocol.B64
import com.mtajchert.carom.app.core.protocol.Crypto

/** The `state` values of `media.state@1` (spec §3.4). */
object MediaStates {
    const val PLAYING = "playing"
    const val PAUSED = "paused"
    const val BUFFERING = "buffering"
    const val STOPPED = "stopped"
}

/** The `controls` values of `media.state@1` and the `command` values of `media.command@1`. */
object MediaControls {
    const val PLAY = "play"
    const val PAUSE = "pause"
    const val NEXT = "next"
    const val PREVIOUS = "previous"
    const val SEEK = "seek"
    val ALL: List<String> = listOf(PLAY, PAUSE, NEXT, PREVIOUS, SEEK)
}

/**
 * What the bridge needs from one media session at one moment. Content fields are plain text;
 * [toString] never prints them.
 */
data class SessionSnapshot(
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val state: String,
    val positionMs: Long?,
    /** Wall-clock time (ms) at which [positionMs] was true. */
    val positionAt: Long?,
    val controls: List<String>,
    /** Identity of the current track. Art is encoded and uploaded again only when it changes. */
    val artKey: String,
) {
    override fun toString() = "SessionSnapshot(state=$state)" // never log content
}

/** Album art ready to seal: WebP bytes and their pixel size. */
class EncodedArt(val bytes: ByteArray, val width: Int, val height: Int) {
    override fun toString() = "EncodedArt(${width}x$height)" // never log content

    companion object {
        const val MIME = "image/webp"
    }
}

/** One live media session. The Android implementation wraps a `MediaController`. */
interface MediaSessionHandle {
    val packageName: String
    val appLabel: String
    val tag: String?

    fun snapshot(): SessionSnapshot

    /** The current track's art, or null when there is none. Blocking work runs off the caller's thread. */
    suspend fun loadArt(): EncodedArt?

    /** [onChange] fires on metadata, playback-state and session-destroyed callbacks. Close to unregister. */
    fun registerCallback(onChange: () -> Unit): AutoCloseable

    /** Runs a [MediaControls] command through the transport controls. False when it could not run. */
    fun perform(command: String, positionMs: Long?): Boolean
}

/** Opens the media session behind a notification, or null when it has none. */
fun interface MediaSessionSource {
    fun open(sbn: StatusBarNotification): MediaSessionHandle?
}

object MediaSessionIds {
    private const val BYTES = 16

    /** `b64url(sha256(package + NUL + tag)[0..16])`: the same session keeps its id across reposts and restarts. */
    fun of(packageName: String, tag: String?): String =
        B64.encode(Crypto.sha256("$packageName\u0000${tag.orEmpty()}".toByteArray(Charsets.UTF_8)).copyOf(BYTES))
}
