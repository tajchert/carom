package com.mtajchert.carom.app.core.data.runtime

import com.mtajchert.carom.app.core.data.sync.CaromSync
import com.mtajchert.carom.app.core.hub.Backoff
import com.mtajchert.carom.app.core.hub.HubErrorCode
import com.mtajchert.carom.app.core.hub.HubException
import com.mtajchert.carom.app.core.hub.HubMessage
import com.mtajchert.carom.app.core.hub.HubSocket
import com.mtajchert.carom.app.core.hub.HubSocketEvent
import com.mtajchert.carom.app.core.model.ConnectionStatus
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import javax.inject.Inject
import javax.inject.Singleton

/** What the foreground WebSocket reports to the rest of the app. */
interface LiveHubConnection {
    /** True while a WebSocket to the Hub is open (the app is visible). */
    val connected: StateFlow<Boolean>

    /**
     * `pair` frames from the live socket (SPEC §7): a candidate for an offer this device made.
     * Untrusted until `Enrollment.verify` accepts it; polling `GET /v1/pair/:id` still works.
     */
    val pairCandidates: SharedFlow<HubSocketEvent.PairCandidate>
}

/** The shared, observable state behind [LiveHubConnection]; written by [ForegroundSocketSession]. */
@Singleton
class LiveConnectionState @Inject constructor() : LiveHubConnection {
    private val connectedFlow = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = connectedFlow.asStateFlow()

    private val pairs = MutableSharedFlow<HubSocketEvent.PairCandidate>(extraBufferCapacity = PAIR_BUFFER)
    override val pairCandidates: SharedFlow<HubSocketEvent.PairCandidate> = pairs.asSharedFlow()

    internal fun setConnected(value: Boolean) {
        connectedFlow.value = value
    }

    internal fun emitPair(candidate: HubSocketEvent.PairCandidate) {
        pairs.tryEmit(candidate)
    }

    private companion object {
        const val PAIR_BUFFER = 4
    }
}

/**
 * The foreground-only WebSocket (docs/CLIENTS.md "Connection policy (phone)"): connects, hands
 * every frame to the sync engine, acknowledges, and reconnects with jittered backoff (SPEC §7)
 * until the coroutine running [run] is cancelled (the app went to the background).
 *
 * Platform-free so it is tested with a fake socket; [ForegroundSync] runs it on lifecycle events.
 */
class ForegroundSocketSession(
    private val sync: CaromSync,
    private val state: LiveConnectionState,
    /** Opens a socket for the enrolled Hub, or null when this device is not paired. */
    private val connect: suspend () -> HubSocket?,
    private val backoff: Backoff = Backoff(),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private enum class Next { RECONNECT, STOP }

    suspend fun run() {
        try {
            while (currentCoroutineContext().isActive) {
                val socket = connect() ?: return
                val next = try {
                    pump(socket)
                } finally {
                    state.setConnected(false)
                    socket.close()
                }
                if (next == Next.STOP) return
                sleep(backoff.next())
            }
        } finally {
            state.setConnected(false)
        }
    }

    private suspend fun pump(socket: HubSocket): Next {
        var next = Next.RECONNECT
        var wiped = false
        socket.events.collect { event ->
            when (event) {
                HubSocketEvent.Open -> {
                    backoff.reset()
                    state.setConnected(true)
                }
                is HubSocketEvent.Message ->
                    sync.process(listOf(HubMessage(event.seq, event.envelope)))?.let { socket.ack(it) }
                HubSocketEvent.DirectoryChanged -> sync.refreshDirectory()
                is HubSocketEvent.PairCandidate -> state.emitPair(event)
                HubSocketEvent.Revoked -> {
                    // SPEC §5.4: the revoked frame wipes right away.
                    if (!wiped) sync.onRevoked()
                    wiped = true
                    next = Next.STOP
                }
                is HubSocketEvent.Closed -> if (event.code == HubSocket.REVOKED_CLOSE_CODE) {
                    if (!wiped) sync.onRevoked()
                    wiped = true
                    next = Next.STOP
                }
                is HubSocketEvent.Failure -> {
                    val error = event.error
                    if (error is HubException.Http && error.code == HubErrorCode.UNAUTHORIZED) {
                        // Let the engine see the 401 on a normal route, which latches "pair again".
                        sync.fetchMessages()
                        if (sync.status.value == ConnectionStatus.REJECTED) next = Next.STOP
                    }
                }
            }
        }
        return next
    }
}
