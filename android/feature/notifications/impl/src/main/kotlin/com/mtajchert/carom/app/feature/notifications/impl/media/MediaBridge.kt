package com.mtajchert.carom.app.feature.notifications.impl.media

import android.service.notification.StatusBarNotification
import com.mtajchert.carom.app.core.data.repository.UserPreferencesRepository
import com.mtajchert.carom.app.core.data.runtime.ActivitySignal
import com.mtajchert.carom.app.core.data.sync.CaromSync
import com.mtajchert.carom.app.core.data.sync.SendResult
import com.mtajchert.carom.app.core.data.work.SyncWorkScheduler
import com.mtajchert.carom.app.core.model.AppForwardingMode
import com.mtajchert.carom.app.core.protocol.MessageBodies
import com.mtajchert.carom.app.core.protocol.MessageTypes
import com.mtajchert.carom.app.feature.notifications.impl.DefaultBlobUploader
import com.mtajchert.carom.app.feature.notifications.impl.ForwardingEnvironment
import com.mtajchert.carom.app.feature.notifications.impl.NotificationAppsRepository
import com.mtajchert.carom.app.feature.notifications.impl.NotificationFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The phone side of the Mac's now-playing card (spec §5.2). While the listener is connected it
 * follows the media sessions behind media notifications and sends `media.state@1`:
 *
 * - on metadata and playback-state callbacks only, at most once per [DEBOUNCE_MS]; the state is
 *   read when the timer fires, so a burst sends the latest. Position alone never triggers a send:
 *   the Mac extrapolates from `position_ms` and `position_at`, so a flush whose body differs from
 *   the last one only in those two fields is dropped.
 * - album art goes out as a blob once per track, and again when the track plays on past
 *   [ART_REUSE_MS], so a new message never points at a blob close to expiry.
 * - when the media notification is removed, `state: stopped` is sent at once.
 *
 * Like [com.mtajchert.carom.app.feature.notifications.impl.NotificationForwarder], listener
 * callbacks only enqueue; one consumer coroutine owns all changes to the tracked sessions.
 */
@Singleton
class MediaBridge @Inject constructor(
    private val source: MediaSessionSource,
    private val sync: CaromSync,
    private val scheduler: SyncWorkScheduler,
    private val uploader: MediaArtUploader,
    private val apps: NotificationAppsRepository,
    private val preferences: UserPreferencesRepository,
    private val activity: Optional<ActivitySignal>,
) {
    private sealed interface Event {
        class Posted(val sbn: StatusBarNotification) : Event
        class Removed(val key: String) : Event
        class Changed(val session: String) : Event
        class Flush(val session: String) : Event
    }

    private class Tracked(
        val key: String,
        val session: String,
        val handle: MediaSessionHandle,
        val app: MessageBodies.App,
    ) {
        var registration: AutoCloseable? = null
        var pending: Job? = null

        /** The track whose art [art] belongs to; null until its art was loaded (or found missing). */
        var artKey: String? = null
        var art: MessageBodies.BlobRef? = null

        /** When [art] was uploaded. */
        var artAt: Long = 0

        /** The last body sent, to drop flushes that only moved the position. */
        var lastSent: MessageBodies.MediaState? = null

        fun release() {
            pending?.cancel()
            registration?.close()
        }
    }

    private class Run(val scope: CoroutineScope, val env: ForwardingEnvironment) {
        val events = Channel<Event>(Channel.UNLIMITED)
    }

    @Volatile
    private var run: Run? = null
    private val byKey = ConcurrentHashMap<String, Tracked>()
    private val bySession = ConcurrentHashMap<String, Tracked>()

    /** Called when the listener connects. [scope] ends with the connection. */
    fun start(scope: CoroutineScope, env: ForwardingEnvironment) {
        stop()
        val r = Run(scope, env)
        run = r
        scope.launch {
            for (event in r.events) {
                try {
                    handle(r, event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RuntimeException) {
                    env.log("media_failed", e.javaClass.simpleName)
                }
            }
        }
    }

    /** Called when the listener disconnects. Callbacks are unregistered; nothing more is sent. */
    fun stop() {
        run?.events?.close()
        run = null
        byKey.values.forEach(Tracked::release)
        byKey.clear()
        bySession.clear()
    }

    /**
     * Runs [command] on [session] when the session is active, advertises the control, and the
     * arguments are valid. Called from the sync engine's thread; it only reads the maps.
     */
    fun command(session: String, command: String, positionMs: Long?): CommandOutcome {
        val tracked = bySession[session] ?: return CommandOutcome.UNKNOWN_SESSION
        if (command !in MediaControls.ALL) return CommandOutcome.UNSUPPORTED
        if (command == MediaControls.SEEK && (positionMs == null || positionMs < 0)) return CommandOutcome.BAD_ARGUMENT
        if (command !in tracked.handle.snapshot().controls) return CommandOutcome.UNSUPPORTED
        val done = tracked.handle.perform(command, positionMs)
        activity.ifPresent { it.onActivity() }
        return if (done) CommandOutcome.DONE else CommandOutcome.UNSUPPORTED
    }

    fun onPosted(sbn: StatusBarNotification) {
        val r = run ?: return
        if (sbn.packageName == r.env.ownPackage) return
        r.events.trySend(Event.Posted(sbn))
    }

    fun onRemoved(sbn: StatusBarNotification) {
        run?.events?.trySend(Event.Removed(sbn.key))
    }

    private suspend fun handle(r: Run, event: Event) {
        when (event) {
            is Event.Posted -> posted(r, event.sbn)
            is Event.Removed -> byKey[event.key]?.let { stopAndUntrack(r, it) }
            is Event.Changed -> bySession[event.session]?.let { schedule(r, it) }
            is Event.Flush -> bySession[event.session]?.let {
                it.pending = null
                send(r, it)
            }
        }
    }

    private suspend fun posted(r: Run, sbn: StatusBarNotification) {
        val handle = source.open(sbn) ?: return // not a media notification
        val app = apps.recordSeen(handle.packageName, handle.appLabel, r.env.now())
        val existing = byKey[sbn.key]
        if (blocked(r, app.mode)) {
            existing?.let { stopAndUntrack(r, it) }
            return
        }
        val session = MediaSessionIds.of(handle.packageName, handle.tag)
        if (existing?.session == session) return // the player reposted its notification
        existing?.let { stopAndUntrack(r, it) }
        bySession[session]?.takeIf { it.key != sbn.key }?.let(::untrack)

        val tracked = Tracked(
            key = sbn.key,
            session = session,
            handle = handle,
            app = MessageBodies.App(id = handle.packageName, name = app.label.ifBlank { handle.packageName }),
        )
        tracked.registration = handle.registerCallback { r.events.trySend(Event.Changed(session)) }
        byKey[sbn.key] = tracked
        bySession[session] = tracked
        schedule(r, tracked)
    }

    /**
     * True when the per-app mode, the global pause or the locked-only switch forbid forwarding.
     * Title, artist and album are content, so Hide content means no media card at all.
     */
    private suspend fun blocked(r: Run, mode: AppForwardingMode): Boolean {
        val prefs = preferences.userPreferences.first()
        val skip = NotificationFilter.policy(
            mode = mode,
            paused = prefs.forwardingPaused,
            onlyWhenLocked = prefs.forwardOnlyWhenLocked,
            screenLocked = prefs.forwardOnlyWhenLocked && r.env.isScreenLocked(),
        )
        return skip != null || mode == AppForwardingMode.HIDE_CONTENT
    }

    /** One pending flush per session; it reads the newest state when it fires. */
    private fun schedule(r: Run, t: Tracked) {
        if (t.pending?.isActive == true) return
        t.pending = r.scope.launch {
            delay(DEBOUNCE_MS)
            // trySend: stop() may have closed the channel while this job slept.
            r.events.trySend(Event.Flush(t.session))
        }
    }

    private suspend fun send(r: Run, t: Tracked) {
        // Settings may have changed since the notification was posted; re-check before every send.
        val app = apps.recordSeen(t.handle.packageName, t.handle.appLabel, r.env.now())
        if (blocked(r, app.mode)) {
            stopAndUntrack(r, t)
            return
        }
        val snapshot = t.handle.snapshot()
        refreshArt(t, snapshot.artKey, r.env.now())
        val body = MessageBodies.MediaState(
            session = t.session,
            app = t.app,
            title = snapshot.title,
            artist = snapshot.artist,
            album = snapshot.album,
            art = t.art,
            state = snapshot.state,
            positionMs = snapshot.positionMs,
            positionAt = snapshot.positionAt,
            durationMs = snapshot.durationMs,
            controls = snapshot.controls,
        )
        val last = t.lastSent
        if (last != null && last.withoutPosition() == body.withoutPosition() && !positionJumped(last, body)) return
        t.lastSent = body
        post(r, body)
    }

    /**
     * True when the new position departs from what the Mac extrapolates from the last sent anchor
     * (a seek). Ordinary progress ticks stay within [POSITION_TOLERANCE_MS] and send nothing.
     */
    private fun positionJumped(last: MessageBodies.MediaState, body: MessageBodies.MediaState): Boolean {
        val lastPos = last.positionMs
        val lastAt = last.positionAt
        val pos = body.positionMs
        val at = body.positionAt
        if (lastPos == null || lastAt == null || pos == null || at == null) return (lastPos == null) != (pos == null)
        val expected = if (last.state == MediaStates.PLAYING) lastPos + (at - lastAt) else lastPos
        return kotlin.math.abs(pos - expected) > POSITION_TOLERANCE_MS
    }

    private fun MessageBodies.MediaState.withoutPosition() = copy(positionMs = null, positionAt = null)

    /**
     * Loads and uploads the art when the track changed. A failed upload leaves [Tracked.artKey]
     * unchanged, so the next flush tries again; a track with no art is remembered as having none.
     *
     * Like message images ([DefaultBlobUploader.REUSE_MS]) a ref is reused for [ART_REUSE_MS]
     * only; after that the same track's art is uploaded again. If that upload fails the old ref,
     * which still has most of its lifetime left, is kept.
     */
    private suspend fun refreshArt(t: Tracked, artKey: String, now: Long) {
        if (t.artKey == artKey) {
            if (t.art == null || now - t.artAt in 0L until ART_REUSE_MS) return
            val encoded = t.handle.loadArt() ?: return
            uploader.upload(encoded)?.let {
                t.art = it
                t.artAt = now
            }
            return
        }
        t.art = null
        val encoded = t.handle.loadArt()
        if (encoded == null) {
            t.artKey = artKey
            return
        }
        val ref = uploader.upload(encoded) ?: return
        t.art = ref
        t.artAt = now
        t.artKey = artKey
    }

    private suspend fun stopAndUntrack(r: Run, t: Tracked) {
        untrack(t)
        post(r, MessageBodies.MediaState(session = t.session, app = t.app, state = MediaStates.STOPPED, controls = emptyList()))
    }

    private fun untrack(t: Tracked) {
        t.release()
        byKey.remove(t.key, t)
        bySession.remove(t.session, t)
    }

    private suspend fun post(r: Run, body: MessageBodies.MediaState) {
        val result = sync.send(MessageTypes.MEDIA_STATE, MessageBodies.toJson(MessageBodies.MediaState.serializer(), body))
        when (result) {
            is SendResult.Queued -> scheduler.scheduleOutboxFlush()
            is SendResult.Dropped -> r.env.log("media_dropped", result.code)
            SendResult.TooLarge -> r.env.log("media_too_large")
            is SendResult.Sent, SendResult.NoRecipients, SendResult.NotPaired -> Unit
        }
        activity.ifPresent { it.onActivity() }
    }

    companion object {
        const val DEBOUNCE_MS = 1_000L
        const val POSITION_TOLERANCE_MS = 2_000L

        /** How long an uploaded art ref is reused; the blob lives [DefaultBlobUploader.MESSAGE_TTL_S]. */
        const val ART_REUSE_MS = DefaultBlobUploader.REUSE_MS
    }
}
