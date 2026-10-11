package com.mtajchert.carom.app.feature.notifications.impl.media

import android.content.Context
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.mtajchert.carom.app.core.data.runtime.ActivitySignal
import com.mtajchert.carom.app.core.protocol.MessageBodies
import com.mtajchert.carom.app.core.testing.repository.FakeUserPreferencesRepository
import com.mtajchert.carom.app.core.testing.sync.FakeCaromSync
import com.mtajchert.carom.app.core.testing.sync.FakeSyncWorkScheduler
import com.mtajchert.carom.app.feature.notifications.impl.FakeNotificationAppsRepository
import com.mtajchert.carom.app.feature.notifications.impl.ForwardingEnvironment
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import java.util.Optional

val ART_REF = MessageBodies.BlobRef(id = "art-1", key = "k", mime = "image/webp", size = 3, hash = "h", w = 192, h = 192)

class FakeMediaSession(
    override val packageName: String = "com.music",
    override val appLabel: String = "Music",
    override val tag: String? = "main",
) : MediaSessionHandle {
    var snapshot = SessionSnapshot(
        title = "Song",
        artist = "Band",
        album = "LP",
        durationMs = 200_000,
        state = MediaStates.PLAYING,
        positionMs = 1_000,
        positionAt = 5_000,
        controls = listOf("play", "pause", "next", "previous", "seek"),
        artKey = "song",
    )
    var art: EncodedArt? = EncodedArt(byteArrayOf(1, 2, 3), 192, 192)
    var artLoads = 0
    val callbacks = mutableListOf<() -> Unit>()
    val performed = mutableListOf<String>()

    override fun snapshot() = snapshot

    override suspend fun loadArt(): EncodedArt? {
        artLoads++
        return art
    }

    override fun registerCallback(onChange: () -> Unit): AutoCloseable {
        callbacks += onChange
        return AutoCloseable { callbacks -= onChange }
    }

    override fun perform(command: String, positionMs: Long?): Boolean {
        performed += if (positionMs == null) command else "$command@$positionMs"
        return true
    }

    /** What a player's metadata or playback-state callback does. */
    fun change() = callbacks.toList().forEach { it() }
}

/** Maps a notification id to a session. [opened] counts how often a controller was built. */
class FakeMediaSessionSource : MediaSessionSource {
    val sessions = mutableMapOf<Int, FakeMediaSession>()
    var opened = 0

    override fun open(sbn: StatusBarNotification): MediaSessionHandle? = sessions[sbn.id]?.also { opened++ }
}

class FakeArtUploader : MediaArtUploader {
    val uploads = mutableListOf<EncodedArt>()
    var result: MessageBodies.BlobRef? = ART_REF

    override suspend fun upload(art: EncodedArt): MessageBodies.BlobRef? {
        uploads += art
        return result
    }
}

class CountingSignal : ActivitySignal {
    var activity = 0
    val forwarded = mutableListOf<Set<String>>()

    override fun onForwarded(online: Set<String>) {
        forwarded += online
    }

    override fun onActivity() {
        activity++
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MediaHarness(private val context: Context) {
    val sync = FakeCaromSync()
    val scheduler = FakeSyncWorkScheduler()
    val apps = FakeNotificationAppsRepository()
    val prefs = FakeUserPreferencesRepository()
    val source = FakeMediaSessionSource()
    val uploader = FakeArtUploader()
    val signal = CountingSignal()
    var locked = false

    fun start(scope: TestScope): MediaBridge {
        val bridge = MediaBridge(source, sync, scheduler, uploader, apps, prefs, Optional.of(signal))
        bridge.start(
            scope.backgroundScope,
            object : ForwardingEnvironment {
                override val ownPackage = "com.mtajchert.carom.app"
                override val hiddenText = "New notification"
                override fun isScreenLocked() = locked
                override fun now() = scope.testScheduler.currentTime
            },
        )
        return bridge
    }

    @Suppress("DEPRECATION")
    fun sbn(id: Int = 1, pkg: String = "com.music"): StatusBarNotification {
        val n = NotificationCompat.Builder(context, "channel").setSmallIcon(android.R.drawable.ic_dialog_info).build()
        return StatusBarNotification(pkg, pkg, id, null, 10_000, 0, 0, n, Process.myUserHandle(), 1)
    }

    /** Every `media.state@1` sent so far, decoded. */
    fun states(): List<MessageBodies.MediaState> =
        sync.sent.map { MessageBodies.fromJson(MessageBodies.MediaState.serializer(), it.body)!! }
}

/** Lets the consumer run, then waits out the debounce window and lets the flush run. */
@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.settle() {
    runCurrent()
    advanceTimeBy(MediaBridge.DEBOUNCE_MS)
    runCurrent()
}
