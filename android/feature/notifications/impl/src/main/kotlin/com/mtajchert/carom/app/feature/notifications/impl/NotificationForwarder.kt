package com.mtajchert.carom.app.feature.notifications.impl

import com.mtajchert.carom.app.core.data.repository.UserPreferencesRepository
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the forwarder needs from the platform; [CaromListenerService] supplies the Android one. */
interface ForwardingEnvironment {
    val ownPackage: String

    /** "New notification", sent instead of the content for apps set to *Hide content*. */
    val hiddenText: String

    fun isScreenLocked(): Boolean

    fun now(): Long = System.currentTimeMillis()

    /** Codes and ids only; never content. */
    fun log(event: String, detail: String? = null) = Unit
}

/**
 * The phone side of notification mirroring (docs/CLIENTS.md "Notifications").
 *
 * Listener callbacks only enqueue (O(1) on the main thread); one consumer coroutine in [scope]
 * reads, filters, maps and sends events in order, so a `removed` never overtakes its `posted`.
 * Everything stops when [scope] is cancelled (the listener disconnected).
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
) {
    private sealed interface Event {
        class Posted(val read: () -> NotificationSnapshot?) : Event
        class Removed(val key: String) : Event
        class SendThrottled(val key: String) : Event
        class Reconcile(val activeKeys: () -> Set<String>?) : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)

    // Owned by the consumer coroutine only.
    private val pending = HashMap<String, MessageBodies.NotificationPosted>()
    private val trailing = HashMap<String, Job>()
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
    fun onPosted(read: () -> NotificationSnapshot?) {
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

    private suspend fun handle(event: Event) = when (event) {
        is Event.Posted -> event.read()?.let { posted(it) }
        is Event.Removed -> removed(event.key)
        is Event.SendThrottled -> {
            trailing.remove(event.key)
            pending.remove(event.key)?.let { sendPosted(it) }
        }
        is Event.Reconcile -> event.activeKeys()?.let { active ->
            for (key in forwarded.all() - active) removed(key)
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

        val body = NotificationMapper.posted(
            n = n,
            appName = app.label,
            hideContent = app.mode == AppForwardingMode.HIDE_CONTENT,
            hiddenText = env.hiddenText,
        )
        val wait = throttle.delayFor(n.key, now)
        if (wait > 0) {
            // Keep only the newest update and send it when the window ends.
            pending[n.key] = body
            if (trailing[n.key]?.isActive != true) {
                trailing[n.key] = scope.launch {
                    delay(wait)
                    events.send(Event.SendThrottled(n.key))
                }
            }
            return
        }
        sendPosted(body)
    }

    private suspend fun sendPosted(body: MessageBodies.NotificationPosted) {
        throttle.markSent(body.key, env.now())
        var result = sync.send(MessageTypes.NOTIFICATION_POSTED, NotificationMapper.postedJson(body))
        if (result == SendResult.TooLarge) {
            // Only JSON escaping can push 3 x 4000 characters past the limit; send a shorter copy.
            val short = body.copy(
                title = body.title?.let { NotificationMapper.truncate(it, FALLBACK_CHARS) },
                text = body.text?.let { NotificationMapper.truncate(it, FALLBACK_CHARS) },
                subText = body.subText?.let { NotificationMapper.truncate(it, FALLBACK_CHARS) },
            )
            result = sync.send(MessageTypes.NOTIFICATION_POSTED, NotificationMapper.postedJson(short))
        }
        if (result is SendResult.Sent || result is SendResult.Queued) forwarded.add(body.key, env.now())
        after(result)
    }

    private suspend fun removed(key: String) {
        trailing.remove(key)?.cancel()
        pending.remove(key)
        throttle.forget(key)
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
        if (fetchJob?.isActive == true || now - lastFetchAt < FETCH_INTERVAL_MS) return
        lastFetchAt = now
        fetchJob = scope.launch { sync.fetchMessages() }
    }

    /** Creates a forwarder bound to one listener connection's scope. */
    class Factory @Inject constructor(
        private val sync: CaromSync,
        private val scheduler: SyncWorkScheduler,
        private val apps: NotificationAppsRepository,
        private val preferences: UserPreferencesRepository,
        private val forwarded: ForwardedKeys,
    ) {
        fun create(scope: CoroutineScope, env: ForwardingEnvironment) =
            NotificationForwarder(scope, sync, scheduler, apps, preferences, forwarded, env)
    }

    companion object {
        const val FETCH_INTERVAL_MS = 15_000L
        private const val FALLBACK_CHARS = 1000
    }
}
