package com.mtajchert.carom.app.feature.notifications.impl.media

import android.media.MediaMetadata
import android.media.session.PlaybackState
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaStateMapperTest {
    private fun playback(state: Int, position: Long = 0, updatedAt: Long = 0, actions: Long = 0): PlaybackState =
        PlaybackState.Builder().setState(state, position, 1f, updatedAt).setActions(actions).build()

    private fun snapshot(p: PlaybackState?, md: MediaMetadata? = null, wall: Long = 1_000_000, elapsed: Long = 10_000) =
        MediaStateMapper.snapshot(md, p, wall, elapsed)

    @Test
    fun `platform states map to the four protocol states`() {
        val expected = mapOf(
            PlaybackState.STATE_PLAYING to MediaStates.PLAYING,
            PlaybackState.STATE_FAST_FORWARDING to MediaStates.PLAYING,
            PlaybackState.STATE_REWINDING to MediaStates.PLAYING,
            PlaybackState.STATE_PAUSED to MediaStates.PAUSED,
            PlaybackState.STATE_BUFFERING to MediaStates.BUFFERING,
            PlaybackState.STATE_CONNECTING to MediaStates.BUFFERING,
            PlaybackState.STATE_SKIPPING_TO_NEXT to MediaStates.BUFFERING,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS to MediaStates.BUFFERING,
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM to MediaStates.BUFFERING,
            PlaybackState.STATE_STOPPED to MediaStates.STOPPED,
            PlaybackState.STATE_NONE to MediaStates.STOPPED,
            PlaybackState.STATE_ERROR to MediaStates.STOPPED,
        )
        expected.forEach { (platform, protocol) -> assertEquals("state $platform", protocol, snapshot(playback(platform)).state) }
        assertEquals(MediaStates.STOPPED, snapshot(null).state)
    }

    @Test
    fun `controls follow the advertised actions in protocol order`() {
        val all = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO
        assertEquals(
            listOf("play", "pause", "next", "previous", "seek"),
            snapshot(playback(PlaybackState.STATE_PLAYING, actions = all)).controls,
        )
        // A combined play/pause action counts as both.
        assertEquals(listOf("play", "pause"), snapshot(playback(PlaybackState.STATE_PAUSED, actions = PlaybackState.ACTION_PLAY_PAUSE)).controls)
        assertEquals(emptyList<String>(), snapshot(playback(PlaybackState.STATE_PAUSED)).controls)
        assertEquals(emptyList<String>(), snapshot(null).controls)
    }

    @Test
    fun `position is stamped with the wall clock time of the sample`() {
        // The sample was taken 2 s ago on the elapsed-realtime clock.
        val s = snapshot(playback(PlaybackState.STATE_PLAYING, position = 30_000, updatedAt = 8_000), wall = 1_000_000, elapsed = 10_000)
        assertEquals(30_000L, s.positionMs)
        assertEquals(998_000L, s.positionAt)
    }

    @Test
    fun `an unknown position or sample time is handled`() {
        val unknown = snapshot(playback(PlaybackState.STATE_PLAYING, position = PlaybackState.PLAYBACK_POSITION_UNKNOWN))
        assertNull(unknown.positionMs)
        assertNull(unknown.positionAt)
        // No sample time recorded: the position is taken as "now".
        assertEquals(1_000_000L, snapshot(playback(PlaybackState.STATE_PAUSED, position = 5, updatedAt = 0)).positionAt)
    }

    @Test
    fun `metadata gives title, artist, album and duration`() {
        val md = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, "Song")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "Band")
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "LP")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, 200_000)
            .build()
        val s = snapshot(playback(PlaybackState.STATE_PLAYING), md)
        assertEquals("Song", s.title)
        assertEquals("Band", s.artist)
        assertEquals("LP", s.album)
        assertEquals(200_000L, s.durationMs)
    }

    @Test
    fun `display fields win, blank fields and zero duration become absent`() {
        val md = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, "Shown")
            .putString(MediaMetadata.METADATA_KEY_TITLE, "Raw")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "  ")
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, "Sub")
            .build()
        val s = snapshot(playback(PlaybackState.STATE_PLAYING), md)
        assertEquals("Shown", s.title)
        assertEquals("Sub", s.artist)
        assertNull(s.album)
        assertNull(s.durationMs)
    }

    @Test
    fun `the art key follows the track, not the position`() {
        fun md(title: String) = MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title).build()
        val a1 = snapshot(playback(PlaybackState.STATE_PLAYING, position = 1_000), md("A"))
        val a2 = snapshot(playback(PlaybackState.STATE_PLAYING, position = 90_000), md("A"))
        val b = snapshot(playback(PlaybackState.STATE_PLAYING, position = 1_000), md("B"))
        assertEquals(a1.artKey, a2.artKey)
        assertNotEquals(a1.artKey, b.artKey)
    }

    @Test
    fun `session ids are stable, short and distinct per package and tag`() {
        val a = MediaSessionIds.of("com.music", "main")
        assertEquals(a, MediaSessionIds.of("com.music", "main"))
        assertEquals(22, a.length)
        assertNotEquals(a, MediaSessionIds.of("com.music", "other"))
        assertNotEquals(a, MediaSessionIds.of("com.other", "main"))
        assertNotEquals(MediaSessionIds.of("ab", "c"), MediaSessionIds.of("a", "bc"))
        assertEquals(MediaSessionIds.of("com.music", null), MediaSessionIds.of("com.music", ""))
    }

    @Test
    fun `toString never prints content`() {
        val md = MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Secret title").build()
        val s = snapshot(playback(PlaybackState.STATE_PLAYING), md)
        assertFalse(s.toString().contains("Secret"))
        assertFalse(EncodedArt(byteArrayOf(1, 2, 3), 2, 2).toString().contains("1, 2"))
    }
}
