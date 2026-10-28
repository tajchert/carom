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
        class Resync(val requester: String, val active: () -> List<NotificationSnapshot>?) : Event
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
                } catch (e: Exception) {
                    // Any failure (an IOException from the hub, a GeneralSecurityException from the keystore) is
                    // logged and the loop goes on: a worker that died here would leave its slot marked running,
                    // and every later state of this key would wait for it forever.
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
            is Event.Resync -> resynced(event.requester, event.active)
            is Event.Reconcile -> event.activeKeys()?.let { active ->
                for (key in forwarded.all() - active) submit(key, Work.Remove)
            }
        }
    }

    /**
     * Answers `notification.resync@1` (SPEC §4.4): sends [requester] a `resync` copy of each
     * active notification that passes the usual filters. Runs beside the live pipeline, since up to
     * [RESYNC_MAX] sends can take a while. [active] runs on the consumer, newest first; null means
     * the list could not be read.
     */
    fun resync(requester: String, active: () -> List<NotificationSnapshot>?) {
        events.trySend(Event.Resync(requester, active))
    }

    private suspend fun resynced(requester: String, active: () -> List<NotificationSnapshot>?) {
        val list = active() ?: return
        scope.launch {
            for (n in list.take(RESYNC_MAX)) {
                try {
                    resyncOne(requester, n)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    env.log("resync_failed", e.javaClass.simpleName)
                }
            }
        }
    }

    private suspend fun resyncOne(requester: String, n: NotificationSnapshot) {
        val admitted = admit(n, resync = true) ?: return
        // Only the per-app icons: they come from the convergent cache; message-scoped images are skipped.
        val sources = n.imageSources.filterKeys { it.convergent && (!admitted.hide || it == ImageSlot.AppIcon) }
        val encoded = if (sources.isEmpty()) emptyMap() else images.load(sources)
        val refs = if (encoded.isEmpty()) emptyMap() else uploader.upload(encoded)
        val work = Work.Post(n.copy(imageSources = emptyMap()), admitted.label, admitted.hide, encoded, UpdateThrottle.Traits(), hash = "")
        val result = sendBody(work, refs, NotificationMapper.MAX_BODY_BYTES, requester)
            .let { if (it == SendResult.TooLarge) sendBody(work, refs, NotificationMapper.FALLBACK_BODY_BYTES, requester) else it }
        // The Mac may now show this key, so a later removal must reach it.
        if (result is SendResult.Sent || result is SendResult.Queued) forwarded.add(n.key, env.now())
        after(result)
    }

    private class Admitted(val label: String, val hide: Boolean)

    /** The filters and per-app mode shared by live posts and resync; null when [n] must not be sent. */
    private suspend fun admit(n: NotificationSnapshot, resync: Boolean): Admitted? {
        if (NotificationFilter.structural(n, env.ownPackage) != null) return null
        val app = apps.recordSeen(n.packageName, n.appLabel, env.now())
        val prefs = preferences.userPreferences.first()
        // A resync is an explicit request, so "only when the screen is locked" does not apply.
        val onlyWhenLocked = prefs.forwardOnlyWhenLocked && !resync
        val skip = NotificationFilter.policy(
            mode = app.mode,
            paused = prefs.forwardingPaused,
            onlyWhenLocked = onlyWhenLocked,
            screenLocked = onlyWhenLocked && env.isScreenLocked(),
        ) ?: NotificationFilter.ongoing(n, app.forwardOngoing)
        if (skip != null) return null
        return Admitted(app.label, hide = app.mode == AppForwardingMode.HIDE_CONTENT)
    }

    private suspend fun posted(n: NotificationSnapshot) {
        val admitted = admit(n, resync = false) ?: return
        val hide = admitted.hide
        // Content-URI grants end with the notification, so images are loaded now, before any upload.
        // Hide content shows the app icon only: nothing else is loaded, let alone uploaded.
        val sources = if (hide) n.imageSources.filterKeys { it == ImageSlot.AppIcon } else n.imageSources
        val encoded = if (sources.isEmpty()) emptyMap() else images.load(sources)
        val plain = NotificationMapper.posted(n, admitted.label, hide)
        submit(
            n.key,
            Work.Post(
                n = n.copy(imageSources = emptyMap()),
                appName = admitted.label,
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
                } catch (e: Exception) {
                    // Any failure (an IOException from the hub, a GeneralSecurityException from the keystore) is
                    // logged and the loop goes on: a worker that died here would leave its slot marked running,
                    // and every later state of this key would wait for it forever.
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
        resyncFor: String? = null,
    ): SendResult {
        var body = NotificationMapper.posted(work.n, work.appName, work.hide, refs, maxBodyBytes = maxBodyBytes)
        if (resyncFor != null) body = body.copy(resync = true)
        var json = NotificationMapper.postedJson(body)
        if (!MessageTypes.isValidBody(MessageTypes.NOTIFICATION_POSTED, json)) {
            // The Mac would reject the whole message; send what V1 knows instead. A code only, never content.
            env.log("notification_invalid_body")
            json = NotificationMapper.postedJson(NotificationMapper.v1Only(body))
        }
        if (resyncFor != null) {
            // Not collapsed: the Hub keeps one message per (sender, collapse) whatever the recipients, so a
            // collapse value would replace a queued copy meant for another desktop (SPEC §4.4).
            return sync.send(
                MessageTypes.NOTIFICATION_POSTED, json,
                onlyTo = setOf(resyncFor), collapse = false, ttlS = RESYNC_TTL_S,
            )
        }
        return sync.send(MessageTypes.NOTIFICATION_POSTED, json)
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
        const val RESYNC_MAX = 50
        private const val RESYNC_TTL_S = 86_400L
    }
}
