package com.mtajchert.carom.app.core.data.sync

import com.mtajchert.carom.app.core.hub.HubClientFactory
import com.mtajchert.carom.app.core.hub.HubException
import com.mtajchert.carom.app.core.protocol.B64
import com.mtajchert.carom.app.core.protocol.DeviceIds
import com.mtajchert.carom.app.core.protocol.Identity
import com.mtajchert.carom.app.core.protocol.Outcome
import com.mtajchert.carom.app.core.protocol.Pairing
import com.mtajchert.carom.app.core.protocol.Protocol
import com.mtajchert.carom.app.core.protocol.QrPayload
import com.mtajchert.carom.app.core.protocol.QrUri
import com.mtajchert.carom.app.core.protocol.Rng
import com.mtajchert.carom.app.core.protocol.SecureRng
import com.mtajchert.carom.app.core.protocol.VerifiedCandidate
import com.mtajchert.carom.app.core.protocol.Welcome
import com.mtajchert.carom.app.core.protocol.WelcomeKey
import com.mtajchert.carom.app.core.protocol.code
import com.mtajchert.carom.app.core.security.SecretVault
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement

/** Persists where this device is enrolled (non-secret), e.g. in DataStore. */
interface EnrollmentStore {
    suspend fun saveEnrollment(hubUrl: String, deviceId: String, deviceName: String)
}

/** Why onboarding or pairing failed; the UI maps each to a short "cause and fix" message. */
enum class EnrollmentError {
    /** No network or the Hub did not answer. */
    UNREACHABLE,

    /** `POST /v1/setup` was already done on this Hub. Join from a paired device instead. */
    ALREADY_SET_UP,

    /** Wrong setup token, or the Hub locked setup after failed attempts. */
    REFUSED,

    /** The pairing expired or was already used. Show a new QR code. */
    EXPIRED,

    /** The candidate or welcome failed verification (wrong secret or a tampering Hub). */
    VERIFICATION_FAILED,

    /** Anything else the Hub rejected. */
    HUB_ERROR,
}

sealed interface EnrollmentResult {
    data class Enrolled(val deviceId: String) : EnrollmentResult
    data class Failed(val error: EnrollmentError) : EnrollmentResult
}

/** A pairing offer shown as a QR code by the inviting device (SPEC §8 step 1). Never log [uri]. */
class Invite(val hubUrl: String, val pairId: String, val pairSecret: ByteArray, val expiresAt: Long) {
    val uri: String get() = QrUri.buildPair(hubUrl, pairId, pairSecret)
    override fun toString(): String = "Invite($pairId)"
}

sealed interface CandidateCheck {
    /** Nobody has scanned the code yet. */
    data object Waiting : CandidateCheck

    /** A candidate passed mac, pop and device_id; ask the user, showing [fingerprint]. */
    data class Verified(val candidate: VerifiedCandidate) : CandidateCheck {
        val fingerprint: String get() = DeviceIds.fingerprint(candidate.deviceId)
    }

    /** A candidate failed verification: reject it and warn the user (SPEC §8 step 3). */
    data class Rejected(val code: String) : CandidateCheck

    data class Failed(val error: EnrollmentError) : CandidateCheck
}

/**
 * Onboarding and pairing (SPEC §8, docs/CLIENTS.md "Onboarding" and "Pairing UI").
 *
 * The joining side is [setUpNewHub] or [join]; the inviting side is [createInvite],
 * [checkCandidate], [approve] and [reject].
 */
class Enrollment(
    private val vault: SecretVault,
    private val hubFactory: HubClientFactory,
    private val hubProvider: HubProvider,
    private val store: EnrollmentStore,
    private val sync: CaromSync,
    private val clock: Clock = Clock.SYSTEM,
    private val rng: Rng = SecureRng,
    private val pollIntervalMs: Long = WELCOME_POLL_MS,
) {
    /** First device: `POST /v1/setup`, generate K (kv 1), publish the record. */
    suspend fun setUpNewHub(payload: QrPayload.Setup, deviceName: String): EnrollmentResult {
        val identity = Identity.generate(rng)
        val client = hubFactory.client(payload.hub) { null }
        val credentials = try {
            client.setup(payload.setupToken, B64.encode(identity.signPub), B64.encode(identity.kxPub))
        } catch (e: HubException) {
            return EnrollmentResult.Failed(errorOf(e, setup = true))
        }
        if (credentials.deviceId != identity.deviceId) return EnrollmentResult.Failed(EnrollmentError.VERIFICATION_FAILED)
        val now = clock.now()
        vault.update {
            it.copy(identity = identity, deviceToken = credentials.token, groupKeys = emptyList())
                .withGroupKey(1, rng.bytes(Protocol.KEY_LENGTH), now)
        }
        return finish(payload.hub, identity.deviceId, deviceName)
    }

    /**
     * Joining device B: accept, then poll the welcome every 2 s for up to 5 minutes.
     * [onAccepted] receives the fingerprint to show while waiting for approval.
     */
    suspend fun join(payload: QrPayload.Pair, deviceName: String, onAccepted: (fingerprint: String) -> Unit = {}): EnrollmentResult {
        val identity = Identity.generate(rng)
        val anonymous = hubFactory.client(payload.hub) { null }
        val credentials = try {
            anonymous.pairAccept(Pairing.buildAccept(payload.pairId, payload.pairSecret, identity))
        } catch (e: HubException) {
            return EnrollmentResult.Failed(errorOf(e, setup = false))
        }
        if (credentials.deviceId != identity.deviceId) return EnrollmentResult.Failed(EnrollmentError.VERIFICATION_FAILED)
        onAccepted(DeviceIds.fingerprint(identity.deviceId))
        val client = hubFactory.client(payload.hub) { credentials.token }

        val deadline = clock.now() + PAIRING_TTL_MS
        while (clock.now() <= deadline) {
            val box = try {
                client.pairWelcomeBox(payload.pairId)
            } catch (e: HubException) {
                if (e is HubException.Http && !e.isRetryable) return EnrollmentResult.Failed(errorOf(e, setup = false))
                null
            }
            if (box != null) {
                val welcome = when (val opened = Pairing.openWelcome(box, payload.pairId, identity.deviceId, identity.kxSk, payload.pairSecret)) {
                    is Outcome.Ok -> opened.value
                    is Outcome.Err -> return EnrollmentResult.Failed(EnrollmentError.VERIFICATION_FAILED)
                }
                val now = clock.now()
                vault.update {
                    welcome.keys.fold(it.copy(identity = identity, deviceToken = credentials.token, groupKeys = emptyList())) { acc, k ->
                        acc.withGroupKey(k.kv, k.key, now)
                    }
                }
                return finish(payload.hub, identity.deviceId, deviceName)
            }
            delay(pollIntervalMs)
        }
        return EnrollmentResult.Failed(EnrollmentError.EXPIRED)
    }

    private suspend fun finish(hubUrl: String, deviceId: String, deviceName: String): EnrollmentResult {
        store.saveEnrollment(hubUrl, deviceId, deviceName)
        // Publishing and the first directory refresh may fail offline; both are retried later.
        sync.publishOwnRecord()
        sync.refreshDirectory()
        return EnrollmentResult.Enrolled(deviceId)
    }

    /** Inviter step 1: `POST /v1/pair/offer` and a fresh pair secret. */
    suspend fun createInvite(): Pair<Invite?, EnrollmentError?> {
        val client = hubProvider.client() ?: return null to EnrollmentError.HUB_ERROR
        return try {
            val offer = client.pairOffer()
            Invite(client.baseUrl, offer.pairId, rng.bytes(32), offer.expiresAt) to null
        } catch (e: HubException) {
            null to errorOf(e, setup = false)
        }
    }

    /** Inviter step 3 by polling `GET /v1/pair/:id` (the WebSocket `pair` frame can call [verify] directly). */
    suspend fun checkCandidate(invite: Invite): CandidateCheck {
        val client = hubProvider.client() ?: return CandidateCheck.Failed(EnrollmentError.HUB_ERROR)
        val status = try {
            client.pairStatus(invite.pairId)
        } catch (e: HubException) {
            return CandidateCheck.Failed(errorOf(e, setup = false))
        }
        val candidate = status.candidate ?: return CandidateCheck.Waiting
        return verify(invite, candidate)
    }

    fun verify(invite: Invite, candidate: JsonElement): CandidateCheck =
        when (val result = Pairing.verifyCandidate(invite.pairId, invite.pairSecret, candidate)) {
            is Outcome.Ok -> CandidateCheck.Verified(result.value)
            is Outcome.Err -> CandidateCheck.Rejected(result.code)
        }

    /** Inviter step 4: send every group key we hold in the welcome box, then refresh the directory. */
    suspend fun approve(invite: Invite, candidate: VerifiedCandidate): EnrollmentResult {
        val client = hubProvider.client() ?: return EnrollmentResult.Failed(EnrollmentError.HUB_ERROR)
        val contents = vault.load()
        val identity = contents.identity ?: return EnrollmentResult.Failed(EnrollmentError.HUB_ERROR)
        val welcome = Welcome(contents.groupKeys.sortedBy { it.kv }.map { WelcomeKey(it.kv, it.key) }, identity.deviceId)
        val box = Pairing.sealWelcome(invite.pairId, candidate.deviceId, candidate.kxPub, invite.pairSecret, welcome, rng = rng)
        return try {
            client.pairWelcome(invite.pairId, box)
            invite.pairSecret.fill(0) // wiped on use (SPEC §8 step 3)
            sync.refreshDirectory()
            EnrollmentResult.Enrolled(candidate.deviceId)
        } catch (e: HubException) {
            EnrollmentResult.Failed(errorOf(e, setup = false))
        }
    }

    suspend fun reject(invite: Invite) {
        invite.pairSecret.fill(0)
        try {
            hubProvider.client()?.pairReject(invite.pairId)
        } catch (_: HubException) {
            // The offer expires on its own after 5 minutes.
        }
    }

    private fun errorOf(e: HubException, setup: Boolean): EnrollmentError = when (e) {
        is HubException.Http -> when (e.code.code) {
            "conflict" -> if (setup) EnrollmentError.ALREADY_SET_UP else EnrollmentError.EXPIRED
            "forbidden", "unauthorized", "rate_limited" -> EnrollmentError.REFUSED
            "not_found", "gone" -> EnrollmentError.EXPIRED
            else -> if (e.isRetryable) EnrollmentError.UNREACHABLE else EnrollmentError.HUB_ERROR
        }
        else -> EnrollmentError.UNREACHABLE
    }

    companion object {
        const val WELCOME_POLL_MS = 2_000L
        const val PAIRING_TTL_MS = 5 * 60_000L
    }
}
