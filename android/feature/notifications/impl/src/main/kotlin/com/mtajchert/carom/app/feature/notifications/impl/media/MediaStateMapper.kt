package com.mtajchert.carom.app.feature.notifications.impl.media

import android.media.MediaMetadata
import android.media.session.PlaybackState

/** Pure mapping from the platform's session objects to a [SessionSnapshot] (spec §3.4). */
object MediaStateMapper {
    fun snapshot(
        metadata: MediaMetadata?,
        playback: PlaybackState?,
        wallNowMs: Long,
        elapsedNowMs: Long,
    ): SessionSnapshot {
        val title = metadata.text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: metadata.text(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata.text(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata.text(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val album = metadata.text(MediaMetadata.METADATA_KEY_ALBUM)
        val position = playback?.position?.takeIf { it >= 0 }
        val positionAt = if (playback != null && position != null) {
            val sampledAt = playback.lastPositionUpdateTime
            if (sampledAt <= 0) wallNowMs else wallNowMs - (elapsedNowMs - sampledAt).coerceAtLeast(0)
        } else {
            null
        }
        return SessionSnapshot(
            title = title,
            artist = artist,
            album = album,
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 },
            state = stateOf(playback),
            positionMs = position,
            positionAt = positionAt,
            controls = controlsOf(playback?.actions ?: 0L),
            artKey = listOf(metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID), title, artist, album)
                .joinToString("\u0000") { it.orEmpty() },
        )
    }

    private fun MediaMetadata?.text(key: String): String? = this?.getString(key)?.takeIf { it.isNotBlank() }

    private fun stateOf(playback: PlaybackState?): String = when (playback?.state) {
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING -> MediaStates.PLAYING
        PlaybackState.STATE_PAUSED -> MediaStates.PAUSED
        PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING, PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS, PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
        -> MediaStates.BUFFERING
        else -> MediaStates.STOPPED
    }

    private fun controlsOf(actions: Long): List<String> = buildList {
        if ((actions and (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE)) != 0L) add(MediaControls.PLAY)
        if ((actions and (PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)) != 0L) add(MediaControls.PAUSE)
        if ((actions and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L) add(MediaControls.NEXT)
        if ((actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L) add(MediaControls.PREVIOUS)
        if ((actions and PlaybackState.ACTION_SEEK_TO) != 0L) add(MediaControls.SEEK)
    }
}
