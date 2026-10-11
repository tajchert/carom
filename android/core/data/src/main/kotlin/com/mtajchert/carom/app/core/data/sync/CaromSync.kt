package com.mtajchert.carom.app.core.data.sync

import com.mtajchert.carom.app.core.hub.HubClient
import com.mtajchert.carom.app.core.hub.HubMessage
import com.mtajchert.carom.app.core.model.ConnectionStatus
import com.mtajchert.carom.app.core.protocol.DeviceKind
import com.mtajchert.carom.app.core.protocol.MessageBodies
import com.mtajchert.carom.app.core.protocol.ReceiveRule
import com.mtajchert.carom.app.core.protocol.Received
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/**
 * The shared sync engine (docs/CLIENTS.md "Sync engine"): directory, sending through the outbox,
 * receiving with the SPEC §3.3 checks, keyring/rekey and revocation.
 *
 * Every function is safe to call from any coroutine; work is serialized internally. Functions
 * never throw for Hub or network failures: they report them in their result and in [status].
 * Android wiring (listener callbacks, WorkManager, foreground WebSocket) calls into this.
 */
interface CaromSync {
    val status: StateFlow<ConnectionStatus>

    /**
     * Seals [body] as a [type] message, stores it in the outbox and tries to post it right away.
     *
     * @param replyTo msg_id this replies to; the message then goes only to [replyToSender].
     * @param onlyTo optional user selection ("Send clipboard to"), intersected with the routing rules.
     */
    suspend fun send(
        type: String,
        body: JsonObject,
        replyTo: String? = null,
        replyToSender: String? = null,
        onlyTo: Set<String>? = null,
    ): SendResult

    /** Posts every due outbox item. Returns the earliest time more work is due, or null. */
    suspend fun flushOutbox(): FlushResult

    /**
     * Seals and uploads one image (SPEC V2 §3.2, §3.7). [convergent] derives the key from the
     * current group key and the image and checks with `HEAD ?touch=1` first, so one icon is
     * uploaded once per key version; otherwise the key is random. Use a [msgId] only for message
     * images, so the Hub deletes them when that message is superseded.
     *
     * Returns null on any failure (not paired, too large, network, quota): the caller drops that
     * image and still sends the notification. Does CPU work, so call it off the main thread.
     */
    suspend fun uploadBlob(
        plain: ByteArray,
        mime: String,
        w: Int?,
        h: Int?,
        convergent: Boolean,
        ttlS: Long,
        msgId: String? = null,
    ): MessageBodies.BlobRef?

    /** `GET /v1/messages` until drained, handling and acknowledging every page (foreground, after send). */
    suspend fun fetchMessages(): FetchResult

    /** Runs SPEC §3.3 on [messages] (from a fetch or a WebSocket), dispatches them and returns the highest seq to ack. */
    suspend fun process(messages: List<HubMessage>): Long?

    /** `GET /v1/devices`, opening every record (SPEC §5.3) and caching the trusted set; then checks the keyring. */
    suspend fun refreshDirectory(): DirectoryResult

    /** `GET /v1/keyring?have=kv`: opens and stores newer group keys, then republishes our record. */
    suspend fun checkKeyring(): KeyringResult

    /** Generates kv+1 and boxes it for every remaining member except [excluding] (SPEC §5.5). */
    suspend fun rekey(excluding: Set<String> = emptySet()): RekeyResult

    /** Revokes [deviceId] (any member, including this one), then rekeys (SPEC §5.4). */
    suspend fun revoke(deviceId: String): RevokeResult

    /**
     * Seals and publishes this device's record under the current key. Its `receive` rules come
     * from [LocalDeviceProfile.receive] (the user's choice), else the last published record, else
     * the SPEC §5.1 defaults for its kind.
     */
    suspend fun publishOwnRecord(): Boolean

    /** The Hub said this device is revoked: wipe keys and local state, return to onboarding. */
    suspend fun onRevoked()
}

sealed interface SendResult {
    /**
     * Posted (or a duplicate/superseded post, which is also final). [online] holds the recipients
     * that had a live WebSocket when the Hub accepted the message (SPEC V2 §3.7).
     */
    data class Sent(val msgId: String, val online: Set<String> = emptySet()) : SendResult

    /** Stored in the outbox; it will be retried (network error, 5xx). */
    data class Queued(val msgId: String) : SendResult

    /** The payload does not fit the 65 536-byte plaintext limit (SPEC §3). Nothing was stored. */
    data object TooLarge : SendResult

    /** No device wants this type from us (SPEC §5.2). Nothing was stored. */
    data object NoRecipients : SendResult

    /** The Hub refused the message for good; [code] is the Hub error code. */
    data class Dropped(val msgId: String, val code: String) : SendResult

    /** This device is not paired. */
    data object NotPaired : SendResult
}

data class FlushResult(val sent: Int, val dropped: Int, val remaining: Int, val nextAttemptAt: Long?)

sealed interface FetchResult {
    data class Fetched(val handled: Int, val dropped: Int) : FetchResult
    data object NotPaired : FetchResult
    data class Failed(val retryable: Boolean) : FetchResult
}

sealed interface DirectoryResult {
    data class Refreshed(val trusted: Int, val untrusted: Int, val removed: List<String>) : DirectoryResult
    data object NotPaired : DirectoryResult
    data class Failed(val retryable: Boolean) : DirectoryResult
}

sealed interface KeyringResult {
    data class Updated(val newKvs: List<Long>) : KeyringResult
    data object UpToDate : KeyringResult
    data object NotPaired : KeyringResult
    data class Failed(val retryable: Boolean) : KeyringResult
}

sealed interface RekeyResult {
    data class Rekeyed(val kv: Long) : RekeyResult

    /** Another device rekeyed first (409); its key was fetched instead. */
    data object Conflict : RekeyResult
    data object NotPaired : RekeyResult
    data class Failed(val retryable: Boolean) : RekeyResult
}

sealed interface RevokeResult {
    /** Revoked and rekeyed. */
    data object Revoked : RevokeResult

    /** Revoked this device: everything was wiped. */
    data object RevokedSelf : RevokeResult

    /** Revoked, but the rekey failed; it is retried on the next directory refresh. */
    data object RevokedRekeyPending : RevokeResult
    data object NotPaired : RevokeResult
    data class Failed(val retryable: Boolean) : RevokeResult
}

/**
 * Handles one v1 message type after it passed every SPEC §3.3 check. Bound into a Hilt set
 * (`@IntoSet`); the set's types are this device's `caps` and known types.
 *
 * Throwing (other than cancellation) drops the message; it is still acknowledged.
 */
interface IncomingMessageHandler {
    val type: String
    suspend fun handle(message: Received)
}

/** What this device says about itself in its record. */
data class LocalDeviceProfile(
    val name: String,
    val kind: DeviceKind,
    val platform: String,
    val appVersion: String,
    /** Overrides of the default receive rules (SPEC §5.1), by type. */
    val receive: Map<String, ReceiveRule>? = null,
)

fun interface LocalDeviceProfileProvider {
    suspend fun profile(): LocalDeviceProfile
}

/** The Hub client for the enrolled Hub, or null before onboarding. */
fun interface HubProvider {
    suspend fun client(): HubClient?
}
