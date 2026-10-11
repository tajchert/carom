package com.mtajchert.carom.app.feature.notifications.impl

import com.mtajchert.carom.app.core.data.repository.UserPreferencesRepository
import com.mtajchert.carom.app.core.data.runtime.ActivitySignal
import com.mtajchert.carom.app.core.data.sync.CaromSync
import com.mtajchert.carom.app.core.data.sync.SendResult
import com.mtajchert.carom.app.core.data.work.SyncWorkScheduler
import com.mtajchert.carom.app.core.model.AppForwardingMode
import com.mtajchert.carom.app.core.protocol.MessageBodies
import com.mtajchert.carom.app.core.protocol.MessageTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Optional
import javax.inject.Inject

/** What the forwarder needs from the platform; [CaromListenerService] supplies the Android one. */
interface ForwardingEnvironment {
    val ownPackage: String

    fun isScreenLocked(): Boolean

    fun now(): Long = System.currentTimeMillis()

    /** Codes and ids only; never content. */
    fun log(event: String, detail: String? = null) = Unit
}

/** Used until the action window (M3) is bound. */
internal object NoActivitySignal : ActivitySignal {
    override fun onForwarded(online: Set<String>) = Unit

    override fun onActivity() = Unit
}

/**
 * The phone side of notification mirroring (V2 spec §5.1).
 *
 * Listener callbacks only enqueue (O(1) on the main thread). One consumer coroutine in [scope]
 * reads each notification, runs the filters, loads its images and hands the result to a per-key
 * worker. Everything stops when [scope] is cancelled (the listener disconnected).
 *
 * **Per-key ordering.** `msg_id` is assigned when a message is sent, so two sends for one key
 * must never overlap: a slow image upload could otherwise let an older state reach the Mac after
 * a newer one. Each key has at most one worker. The worker takes the newest state (earlier ones
 * that were not yet started are discarded), waits for the throttle window, uploads, sends, and
 * only then looks at the next state. A removal replaces whatever is waiting and runs after the
 * send in flight. Different keys run in parallel; uploads are bounded by [BlobUploader].
 *
 * Sending goes through [CaromSync.send], which routes, seals and stores in the outbox; a queued
 * message is left to WorkManager. `removed` is sent only for keys this phone forwarded; they are
 * kept in Room ([ForwardedKeys]) so a removal is still sent after a listener reconnect or a
 * process restart, and [reconcile] catches removals that happened while the listener was gone.
 */
class NotificationForwarder(
    private val scope: CoroutineScope,
    private val sync: CaromSync,
    private val scheduler: SyncWorkScheduler,
    private val apps: NotificationAppsRepository,
    private val preferences: UserPreferencesRepository,
    private val forwarded: ForwardedKeys,
    private val env: ForwardingEnvironment,
    private val throttle: UpdateThrottle = UpdateThrottle(),
    private val images: ImageLoader = ImageLoader.None,
    private val uploader: BlobUploader = BlobUploader.None,
    private val activity: ActivitySignal = NoActivitySignal,
) {
    private sealed interface Event {
        class Posted(val read: suspend () -> NotificationSnapshot?) : Event
        class Removed(val key: String) : Event
        class Reconcile(val activeKeys: () -> Set<String>?) : Event
    }

    private sealed interface Work {
        class Post(
            val n: NotificationSnapshot,
            val appName: String,
            val hide: Boolean,
            val images: Map<ImageSlot, EncodedImage>,
            val traits: UpdateThrottle.Traits,
            val hash: String,
        ) : Work

        data object Remove : Work
    }

    /** The newest waiting [Work] of one key and whether its worker is running. Guarded by [lock]. */
    private class Slot {
        var work: Work? = null
        var running = false

        /** Wakes a worker that waits for the throttle window: a newer state may skip the wait. */
        val wake = Channel<Unit>(Channel.CONFLATED)
    }

    private val events = Channel<Event>(Channel.UNLIMITED)

    private val lock = Any()
    private val slots = HashMap<String, Slot>()

    private val fetchLock = Any()
    private var lastFetchAt = Long.MIN_VALUE / 2
    private var fetchJob: Job? = null

    init {
        scope.launch {
            for (event in events) {
                try {
                    handle(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RuntimeException) {
                    env.log("notification_failed", e.javaClass.simpleName)
                }
            }
        }
    }

    /** Called on the listener's thread; [read] runs later on the consumer, off the main thread. */
    fun onPosted(read: suspend () -> NotificationSnapshot?) {
        events.trySend(Event.Posted(read))
    }

    fun onRemoved(key: String) {
        events.trySend(Event.Removed(key))
    }

    /**
     * After the listener (re)connects: sends `removed` for every forwarded key that is no longer
     * showing. [activeKeys] runs on the consumer; null means the list could not be read.
     */
    fun reconcile(activeKeys: () -> Set<String>?) {
        events.trySend(Event.Reconcile(activeKeys))
    }

    private suspend fun handle(event: Event) {
        when (event) {
            is Event.Posted -> event.read()?.let { posted(it) }
            is Event.Removed -> submit(event.key, Work.Remove)
            is Event.Reconcile -> event.activeKeys()?.let { active ->
                for (key in forwarded.all() - active) submit(key, Work.Remove)
            }
        }
    }

    private suspend fun posted(n: NotificationSnapshot) {
        if (NotificationFilter.structural(n, env.ownPackage) != null) return
        val now = env.now()
        val app = apps.recordSeen(n.packageName, n.appLabel, now)
        val prefs = preferences.userPreferences.first()
        val skip = NotificationFilter.policy(
            mode = app.mode,
            paused = prefs.forwardingPaused,
            onlyWhenLocked = prefs.forwardOnlyWhenLocked,
            screenLocked = prefs.forwardOnlyWhenLocked && env.isScreenLocked(),
        ) ?: NotificationFilter.ongoing(n, app.forwardOngoing)
        if (skip != null) return

        val hide = app.mode == AppForwardingMode.HIDE_CONTENT
        // Content-URI grants end with the notification, so images are loaded now, before any upload.
        // Hide content shows the app icon only: nothing else is loaded, let alone uploaded.
        val sources = if (hide) n.imageSources.filterKeys { it == ImageSlot.AppIcon } else n.imageSources
        val encoded = if (sources.isEmpty()) emptyMap() else images.load(sources)
        val plain = NotificationMapper.posted(n, app.label, hide)
        submit(
            n.key,
            Work.Post(
                n = n.copy(imageSources = emptyMap()),
                appName = app.label,
                hide = hide,
                images = encoded,
                traits = UpdateThrottle.Traits(
                    progressComplete = n.rich.progress?.complete == true,
                    callType = n.rich.call?.type,
                ),
                hash = NotificationMapper.contentHash(plain, encoded),
            ),
        )
    }

    /** Makes [work] the newest state of [key] and starts the key's worker if none is running. */
    private fun submit(key: String, work: Work) {
        var start = false
        val slot = synchronized(lock) {
            val s = slots.getOrPut(key) { Slot() }
            s.work = work
            if (!s.running) {
                s.running = true
                start = true
            }
            s
        }
        if (start) launchWorker(key, slot) else slot.wake.trySend(Unit)
    }

    private fun launchWorker(key: String, slot: Slot) {
        scope.launch {
            while (true) {
                val work = synchronized(lock) {
                    slot.work ?: run {
                        slot.running = false
                        slots.remove(key)
                        null
                    }
                } ?: break
                try {
                    when (work) {
                        Work.Remove -> if (claim(slot, work)) sendRemoved(key)
                        is Work.Post -> {
                            val wait = synchronized(throttle) { throttle.delayFor(key, env.now(), work.traits) }
                            if (wait > 0) {
                                // Trailing edge: look at the newest state again when the window ends, or earlier when
                                // a newer state arrives (it may be a completed progress bar or a call change).
                                withTimeoutOrNull(wait) { slot.wake.receive() }
                            } else if (claim(slot, work)) {
                                sendPosted(work)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RuntimeException) {
                    env.log("notification_failed", e.javaClass.simpleName)
                }
            }
        }
    }

    /** Takes [work] out of the slot unless a newer state replaced it meanwhile. */
    private fun claim(slot: Slot, work: Work): Boolean = synchronized(lock) {
        if (slot.work === work) {
            slot.work = null
            true
        } else {
            false
        }
    }

    private suspend fun sendPosted(work: Work.Post) {
        val key = work.n.key
        if (synchronized(throttle) { throttle.isDuplicate(key, work.hash) }) return
        val refs = if (work.images.isEmpty()) emptyMap() else uploader.upload(work.images)
        synchronized(throttle) { throttle.markSent(key, env.now(), work.hash, work.traits) }
        var result = sendBody(work, refs, NotificationMapper.MAX_BODY_BYTES)
        if (result == SendResult.TooLarge) {
            // The 64 KB budget leaves headroom, but JSON escaping can still push a body over; retry with a smaller one.
            result = sendBody(work, refs, NotificationMapper.FALLBACK_BODY_BYTES)
        }
        if (result is SendResult.Sent || result is SendResult.Queued) {
            forwarded.add(key, env.now())
        } else {
            synchronized(throttle) { throttle.forgetContent(key) }
        }
        if (result is SendResult.Sent) activity.onForwarded(result.online)
        after(result)
    }

    private suspend fun sendBody(
        work: Work.Post,
        refs: Map<ImageSlot, MessageBodies.BlobRef>,
        maxBodyBytes: Int,
    ): SendResult {
        val body = NotificationMapper.posted(work.n, work.appName, work.hide, refs, maxBodyBytes = maxBodyBytes)
        return sync.send(MessageTypes.NOTIFICATION_POSTED, NotificationMapper.postedJson(body))
    }

    private suspend fun sendRemoved(key: String) {
        synchronized(throttle) { throttle.forget(key) }
        if (!forwarded.remove(key)) return
        after(sync.send(MessageTypes.NOTIFICATION_REMOVED, NotificationMapper.removedJson(key)))
    }

    private fun after(result: SendResult) {
        when (result) {
            is SendResult.Queued -> scheduler.scheduleOutboxFlush()
            is SendResult.Sent -> piggybackFetch()
            is SendResult.Dropped -> env.log("notification_dropped", result.code)
            SendResult.TooLarge -> env.log("notification_too_large")
            SendResult.NoRecipients, SendResult.NotPaired -> Unit
        }
    }

    /** "Fetch after each successful send", coalesced so a burst of notifications fetches once. */
    private fun piggybackFetch() {
        val now = env.now()
        synchronized(fetchLock) {
            if (fetchJob?.isActive == true || now - lastFetchAt < FETCH_INTERVAL_MS) return
            lastFetchAt = now
            fetchJob = scope.launch { sync.fetchMessages() }
        }
    }

    /** Creates a forwarder bound to one listener connection's scope. */
    class Factory @Inject constructor(
        private val sync: CaromSync,
        private val scheduler: SyncWorkScheduler,
        private val apps: NotificationAppsRepository,
        private val preferences: UserPreferencesRepository,
        private val forwarded: ForwardedKeys,
        private val images: ImageLoader,
        private val uploader: BlobUploader,
        private val activity: Optional<ActivitySignal>,
    ) {
        fun create(scope: CoroutineScope, env: ForwardingEnvironment) = NotificationForwarder(
            scope = scope,
            sync = sync,
            scheduler = scheduler,
            apps = apps,
            preferences = preferences,
            forwarded = forwarded,
            env = env,
            images = images,
            uploader = uploader,
            activity = activity.orElse(NoActivitySignal),
        )
    }

    companion object {
        const val FETCH_INTERVAL_MS = 15_000L
    }
}
