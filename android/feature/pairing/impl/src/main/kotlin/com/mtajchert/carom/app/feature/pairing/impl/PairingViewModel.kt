package com.mtajchert.carom.app.feature.pairing.impl

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mtajchert.carom.app.core.data.sync.CandidateCheck
import com.mtajchert.carom.app.core.data.sync.Clock
import com.mtajchert.carom.app.core.data.sync.Enrollment
import com.mtajchert.carom.app.core.data.sync.EnrollmentError
import com.mtajchert.carom.app.core.data.sync.EnrollmentResult
import com.mtajchert.carom.app.core.data.sync.Invite
import com.mtajchert.carom.app.core.protocol.VerifiedCandidate
import com.mtajchert.carom.app.feature.pairing.api.PairCandidateFeed
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Optional
import javax.inject.Inject

/** The inviting side of pairing (docs/CLIENTS.md "Pairing UI"), one state per screen. */
@Immutable
sealed interface PairingUiState {
    /** `POST /v1/pair/offer` is in flight. */
    data object Creating : PairingUiState

    /**
     * The QR code is on screen and we wait for the other device. [uri] carries the pair secret:
     * it is only ever drawn, copied on request, and never logged, so [toString] hides it.
     */
    data class ShowingCode(val uri: String, val secondsLeft: Int) : PairingUiState {
        override fun toString(): String = "ShowingCode(secondsLeft=$secondsLeft)"
    }

    /**
     * A candidate passed mac, pop and device_id: ask "Add device <fingerprint>?" (docs/CLIENTS.md).
     * The candidate carries no kind; that is only known once the new device publishes its record.
     */
    data class Confirm(val fingerprint: String, val approving: Boolean = false) : PairingUiState

    /** The welcome was posted; the new device finishes on its own. */
    data class Added(val fingerprint: String) : PairingUiState

    /** The user said no; the candidate was deleted. */
    data object Declined : PairingUiState

    /**
     * Pairing stopped. [EnrollmentError.VERIFICATION_FAILED] means a candidate failed the checks
     * (a wrong code or a tampering Hub): it was rejected and the user is warned.
     */
    data class Failed(val error: EnrollmentError) : PairingUiState
}

@HiltViewModel
class PairingViewModel internal constructor(
    private val enrollment: Enrollment,
    private val feed: PairCandidateFeed?,
    private val clock: Clock,
    private val pollIntervalMs: Long,
) : ViewModel() {

    @Inject
    constructor(enrollment: Enrollment, feed: Optional<PairCandidateFeed>) :
        this(enrollment, feed.orElse(null), Clock.SYSTEM, POLL_INTERVAL_MS)

    private val state = MutableStateFlow<PairingUiState>(PairingUiState.Creating)
    val uiState: StateFlow<PairingUiState> = state.asStateFlow()

    /** Serializes candidate handling between the poll, a pushed frame and the user's answer. */
    private val mutex = Mutex()
    private var invite: Invite? = null
    private var candidate: VerifiedCandidate? = null
    private var deadline = 0L
    private var creating: Job? = null

    init {
        newCode()
    }

    /** Creates a fresh offer and shows its code. Also "Show a new code" after a failure. */
    fun newCode() {
        if (creating?.isActive == true) return
        creating = viewModelScope.launch {
            mutex.withLock {
                wipe()
                state.value = PairingUiState.Creating
            }
            val (created, error) = enrollment.createInvite()
            mutex.withLock {
                if (created == null) {
                    state.value = PairingUiState.Failed(error ?: EnrollmentError.HUB_ERROR)
                } else {
                    invite = created
                    deadline = clock.now() + Enrollment.PAIRING_TTL_MS
                    state.value = PairingUiState.ShowingCode(created.uri, secondsLeft())
                }
            }
        }
    }

    /**
     * Runs while the screen is visible: ticks the countdown every second, polls
     * `GET /v1/pair/:id` every [pollIntervalMs], and takes candidates pushed over a foreground
     * WebSocket when the app has one. Runs until cancelled; cancel it when the screen stops.
     */
    suspend fun watch() = coroutineScope {
        feed?.let { source ->
            launch {
                source.candidates().collect { pushed ->
                    val current = invite ?: return@collect
                    if (pushed.pairId == current.pairId) onCheck(current, enrollment.verify(current, pushed.candidate))
                }
            }
        }
        var sinceLastPoll = 0L
        while (true) {
            delay(TICK_MS)
            sinceLastPoll += TICK_MS
            val current = invite ?: continue
            if (state.value !is PairingUiState.ShowingCode) continue
            if (tick(current) && sinceLastPoll >= pollIntervalMs) {
                sinceLastPoll = 0
                onCheck(current, enrollment.checkCandidate(current))
            }
        }
    }

    /** Updates the countdown; returns false once the code expired. */
    private suspend fun tick(current: Invite): Boolean = mutex.withLock {
        if (invite !== current || state.value !is PairingUiState.ShowingCode) return@withLock false
        val left = secondsLeft()
        if (left <= 0) {
            wipe()
            state.value = PairingUiState.Failed(EnrollmentError.EXPIRED)
            false
        } else {
            state.value = PairingUiState.ShowingCode(current.uri, left)
            true
        }
    }

    private suspend fun onCheck(current: Invite, check: CandidateCheck) {
        var suspicious = false
        mutex.withLock {
            if (invite !== current || state.value !is PairingUiState.ShowingCode) return
            when (check) {
                CandidateCheck.Waiting -> Unit
                is CandidateCheck.Verified -> {
                    candidate = check.candidate
                    state.value = PairingUiState.Confirm(check.fingerprint)
                }
                is CandidateCheck.Rejected -> {
                    suspicious = true
                    state.value = PairingUiState.Failed(EnrollmentError.VERIFICATION_FAILED)
                }
                // A dropped connection is retried on the next poll; anything else ends this code.
                is CandidateCheck.Failed -> if (check.error != EnrollmentError.UNREACHABLE) {
                    wipe()
                    state.value = PairingUiState.Failed(check.error)
                }
            }
        }
        // SPEC §8 step 3: a candidate that fails verification is rejected (this also wipes the secret).
        if (suspicious) {
            enrollment.reject(current)
            mutex.withLock { if (invite === current) wipe() }
        }
    }

    fun approve() {
        viewModelScope.launch {
            val (current, verified, confirm) = mutex.withLock {
                val confirm = state.value as? PairingUiState.Confirm ?: return@launch
                if (confirm.approving) return@launch
                state.value = confirm.copy(approving = true)
                Triple(invite ?: return@launch, candidate ?: return@launch, confirm)
            }
            val result = enrollment.approve(current, verified)
            mutex.withLock {
                state.value = when (result) {
                    is EnrollmentResult.Enrolled -> PairingUiState.Added(confirm.fingerprint)
                    is EnrollmentResult.Failed -> PairingUiState.Failed(result.error)
                }
                wipe()
            }
        }
    }

    fun reject() {
        viewModelScope.launch {
            val current = mutex.withLock {
                val confirm = state.value as? PairingUiState.Confirm ?: return@launch
                if (confirm.approving) return@launch
                state.value = PairingUiState.Declined
                invite
            }
            current?.let { enrollment.reject(it) }
            mutex.withLock { if (invite === current) wipe() }
        }
    }

    private fun secondsLeft(): Int {
        val left = deadline - clock.now()
        return if (left <= 0) 0 else ((left + 999) / 1000).toInt()
    }

    /** SPEC §8: the pair secret is wiped on use or after 5 minutes, and when the screen goes away. */
    private fun wipe() {
        invite?.pairSecret?.fill(0)
        invite = null
        candidate = null
    }

    override fun onCleared() {
        wipe()
    }

    internal companion object {
        const val POLL_INTERVAL_MS = 2_000L
        const val TICK_MS = 1_000L
    }
}
