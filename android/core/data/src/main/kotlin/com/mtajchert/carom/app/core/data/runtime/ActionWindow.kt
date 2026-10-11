package com.mtajchert.carom.app.core.data.runtime

import com.mtajchert.carom.app.core.data.sync.SyncLog
import com.mtajchert.carom.app.core.model.ActionsFromMac
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** What the rest of the app tells the action window (spec §5.3). */
interface ActivitySignal {
    /** A notification was forwarded; [online] are the recipients with a live socket (`online[]`). */
    fun onForwarded(online: Set<String>)

    /** A received action or a media change: restarts the idle timer of an open window. */
    fun onActivity()
}

/**
 * Keeps the window open while something needs the phone reachable (an incoming or ongoing call,
 * a playing media session). [source] is any stable id such as `call:<key>`; both calls are idempotent.
 */
interface ActionWindowHold {
    fun hold(source: String)
    fun release(source: String)
}

/** True while the Always mode wants a foreground service. */
interface AlwaysOnState {
    val alwaysOn: StateFlow<Boolean>
}

data class ActionWindowSettings(val mode: ActionsFromMac, val windowMinutes: Int, val paired: Boolean)

/**
 * The action window (docs/CLIENTS.md "Connection policy (phone)", spec §5.3): a WebSocket session
 * that is open only while a Mac may want to act on this phone.
 *
 * - After a notification: opens when a forward reaches a desktop that is online, closes after
 *   `windowMinutes` without a forward, received action or media change, and never closes while a
 *   hold is active. A hold release starts a full window again.
 * - Always: open whenever the phone is paired; the session retries with a backoff of up to 15 min.
 * - Off or unpaired: closed.
 *
 * Everything is serialized through one consumer coroutine, so the plain `fun` callbacks are safe
 * from any thread. [runSession] runs a `ForegroundSocketSession` until cancelled or finished;
 * its reconnects happen inside it, so a failing connect is never retried after the window closed.
 */
class ActionWindow(
    private val scope: CoroutineScope,
    private val settings: Flow<ActionWindowSettings>,
    private val hasDesktopOnline: suspend (Set<String>) -> Boolean,
    private val runSession: suspend (maxBackoffMs: Long) -> Unit,
    private val log: SyncLog = SyncLog.NONE,
) : ActivitySignal, ActionWindowHold, AlwaysOnState {

    private sealed interface Event {
        data class Configured(val value: ActionWindowSettings) : Event
        data class Forwarded(val online: Set<String>) : Event
        data object Activity : Event
        data class Held(val source: String, val held: Boolean) : Event
        data class Expired(val timer: Long) : Event
        data class SessionEnded(val session: Long) : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)
    private val openState = MutableStateFlow(false)
    private val alwaysOnState = MutableStateFlow(false)
    private val started = AtomicBoolean(false)

    val isOpen: StateFlow<Boolean> = openState.asStateFlow()
    override val alwaysOn: StateFlow<Boolean> = alwaysOnState.asStateFlow()

    // Owned by the consumer coroutine only.
    private var config = ActionWindowSettings(ActionsFromMac.OFF, ActionsFromMac.DEFAULT_WINDOW_MINUTES, paired = false)
    private val holds = HashSet<String>()
    private var session: Job? = null
    private var sessionId = 0L
    private var timer: Job? = null
    private var timerId = 0L

    /** Starts consuming settings and events. Events sent before this are kept and handled after it. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { settings.collect { events.send(Event.Configured(it)) } }
        scope.launch { for (event in events) handle(event) }
    }

    override fun onForwarded(online: Set<String>) {
        events.trySend(Event.Forwarded(online))
    }

    override fun onActivity() {
        events.trySend(Event.Activity)
    }

    override fun hold(source: String) {
        events.trySend(Event.Held(source, held = true))
    }

    override fun release(source: String) {
        events.trySend(Event.Held(source, held = false))
    }

    private val afterNotification: Boolean
        get() = config.paired && config.mode == ActionsFromMac.AFTER_NOTIFICATION

    private suspend fun handle(event: Event) {
        when (event) {
            is Event.Configured -> configure(event.value)
            is Event.Forwarded -> if (afterNotification) {
                if (openState.value) {
                    arm()
                } else if (hasDesktopOnline(event.online)) {
                    open()
                    arm()
                }
            }
            Event.Activity -> if (afterNotification && openState.value) arm()
            is Event.Held -> {
                val changed = if (event.held) holds.add(event.source) else holds.remove(event.source)
                if (changed && afterNotification && openState.value) arm()
            }
            is Event.Expired -> if (event.timer == timerId && afterNotification && holds.isEmpty()) close()
            is Event.SessionEnded -> if (event.session == sessionId) {
                timer?.cancel()
                timer = null
                session = null
                openState.value = false
            }
        }
    }

    private fun configure(next: ActionWindowSettings) {
        val wasAlways = config.paired && config.mode == ActionsFromMac.ALWAYS
        config = next
        val always = next.paired && next.mode == ActionsFromMac.ALWAYS
        alwaysOnState.value = always
        when {
            always -> if (!wasAlways) {
                close()
                open()
            } else if (session == null) {
                open()
            }
            afterNotification -> if (wasAlways) close() else if (openState.value) arm()
            else -> close()
        }
    }

    private fun open() {
        if (session?.isActive == true) return
        val id = ++sessionId
        val maxBackoff = if (config.mode == ActionsFromMac.ALWAYS) ALWAYS_BACKOFF_MAX_MS else WINDOW_BACKOFF_MAX_MS
        session = scope.launch {
            try {
                runSession(maxBackoff)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.log("action_window_failed", e.javaClass.simpleName)
            } finally {
                events.trySend(Event.SessionEnded(id))
            }
        }
        openState.value = true
        log.log("action_window_opened", null)
    }

    private fun close() {
        timer?.cancel()
        timer = null
        session?.cancel()
        session = null
        sessionId++
        if (openState.value) log.log("action_window_closed", null)
        openState.value = false
    }

    /** (Re)starts the idle timer; with a hold active there is no timer until the last release. */
    private fun arm() {
        timer?.cancel()
        timer = null
        if (holds.isNotEmpty()) return
        val id = ++timerId
        val delayMs = config.windowMinutes * 60_000L
        timer = scope.launch {
            delay(delayMs)
            events.send(Event.Expired(id))
        }
    }

    companion object {
        /** The default reconnect ceiling of [com.mtajchert.carom.app.core.hub.Backoff]. */
        const val WINDOW_BACKOFF_MAX_MS = 60_000L

        /** Always mode, spec §5.3: reconnect backoff up to 15 min. */
        const val ALWAYS_BACKOFF_MAX_MS = 900_000L
    }
}
