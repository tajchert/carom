package com.mtajchert.carom.app.core.data.sync

import com.mtajchert.carom.app.core.hub.HubClient
import com.mtajchert.carom.app.core.hub.HubErrorCode
import com.mtajchert.carom.app.core.hub.HubException
import com.mtajchert.carom.app.core.hub.HubMessage
import com.mtajchert.carom.app.core.model.ConnectionStatus
import com.mtajchert.carom.app.core.protocol.B64
import com.mtajchert.carom.app.core.protocol.Collapse
import com.mtajchert.carom.app.core.protocol.DeviceRecord
import com.mtajchert.carom.app.core.protocol.Envelope
import com.mtajchert.carom.app.core.protocol.Envelopes
import com.mtajchert.carom.app.core.protocol.Identity
import com.mtajchert.carom.app.core.protocol.MessageTypes
import com.mtajchert.carom.app.core.protocol.OpenRecordError
import com.mtajchert.carom.app.core.protocol.Outcome
import com.mtajchert.carom.app.core.protocol.Payload
import com.mtajchert.carom.app.core.protocol.Protocol
import com.mtajchert.carom.app.core.protocol.ReceiveContext
import com.mtajchert.carom.app.core.protocol.ReceiveRule
import com.mtajchert.carom.app.core.protocol.ReceiveError
import com.mtajchert.carom.app.core.protocol.Receiver
import com.mtajchert.carom.app.core.protocol.Records
import com.mtajchert.carom.app.core.protocol.Rekey
import com.mtajchert.carom.app.core.protocol.RekeyMember
import com.mtajchert.carom.app.core.protocol.Rng
import com.mtajchert.carom.app.core.protocol.Routing
import com.mtajchert.carom.app.core.protocol.SecureRng
import com.mtajchert.carom.app.core.protocol.UuidV7Generator
import com.mtajchert.carom.app.core.protocol.code
import com.mtajchert.carom.app.core.protocol.parseJsonOrNull
import com.mtajchert.carom.app.core.security.SecretVault
import com.mtajchert.carom.app.core.security.StoredGroupKey
import com.mtajchert.carom.app.core.security.VaultContents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Wipes local, non-secret state (preferences, caches) after a revocation. */
fun interface LocalStateWiper {
    suspend fun wipe()
}

/**
 * The [CaromSync] implementation. Platform-free: everything it touches is an interface, so it
 * runs unchanged in JVM tests against fakes and against an in-memory Hub.
 *
 * Three locks keep work ordered without risking deadlock (none is taken while holding a later one):
 * outbox posting, receiving, then directory/keyring.
 */
class DefaultCaromSync(
    private val vault: SecretVault,
    private val hubProvider: HubProvider,
    private val outbox: OutboxStore,
    private val seen: SeenMessageStore,
    private val directory: DirectoryStore,
    private val syncState: SyncStateStore,
    private val handlers: Set<IncomingMessageHandler>,
    private val profileProvider: LocalDeviceProfileProvider,
    private val notices: NoticeSink,
    private val localStateWiper: LocalStateWiper,
    private val clock: Clock = Clock.SYSTEM,
    private val ids: UuidV7Generator = UuidV7Generator(clock = clock::now),
    private val rng: Rng = SecureRng,
    private val log: SyncLog = SyncLog.NONE,
) : CaromSync {

    private val outboxLock = Mutex()
    private val receiveLock = Mutex()
    private val directoryLock = Mutex()

    private val statusFlow = MutableStateFlow(ConnectionStatus.IDLE)
    override val status: StateFlow<ConnectionStatus> = statusFlow.asStateFlow()

    /** Types this device handles; also its `caps`. Ordered like [MessageTypes.ALL] for stable records. */
    private val knownTypes: List<String> = handlers.map { it.type }.distinct().sortedBy { type ->
        MessageTypes.ALL.indexOf(type).let { if (it < 0) Int.MAX_VALUE else it }
    }

    /** Identity, current key and client, or null when this device is not paired. */
    private class Session(val contents: VaultContents, val identity: Identity, val key: StoredGroupKey, val client: HubClient)

    private suspend fun session(): Session? {
        val contents = vault.load()
        val identity = contents.identity ?: return null
        val key = contents.currentKey ?: return null
        if (contents.deviceToken == null) return null
        val client = hubProvider.client() ?: return null
        return Session(contents, identity, key, client)
    }

    // -------------------------------------------------------------------------
    // Sending
    // -------------------------------------------------------------------------

    override suspend fun send(
        type: String,
        body: JsonObject,
        replyTo: String?,
        replyToSender: String?,
        onlyTo: Set<String>?,
    ): SendResult = outboxLock.withLock {
        val s = session() ?: return SendResult.NotPaired
        val records = directory.all().mapNotNull { it.record }
        var to = Routing.recipients(type, s.identity.deviceId, records, replyToSender = replyToSender)
        if (onlyTo != null) to = to.filter { it in onlyTo }
        if (to.isEmpty()) return SendResult.NoRecipients

        val now = clock.now()
        val payload = Payload(type = type, ts = now, body = body, replyTo = replyTo)
        if (payload.encode(pad = false).size > Protocol.Limits.MAX_PLAINTEXT) return SendResult.TooLarge
        val msgId = ids.next()
        val ttlS = MessageTypes.defaultTtlS(type)
        val envelope = seal(s.identity, s.key, msgId, to, ttlS, payload)
        val item = OutboxItem(
            msgId = msgId,
            type = type,
            recipients = to,
            envelopeJson = envelope.toJsonString(),
            createdAt = now,
            expiresAt = now + ttlS * 1000,
            state = OutboxState.PENDING,
        )
        outbox.put(item)
        return when (val result = attempt(s, item)) {
            is Attempt.Sent -> SendResult.Sent(msgId)
            is Attempt.Retry -> SendResult.Queued(msgId)
            is Attempt.Dropped -> SendResult.Dropped(msgId, result.code)
        }
    }

    override suspend fun flushOutbox(): FlushResult = outboxLock.withLock {
        val s = session() ?: return FlushResult(0, 0, 0, null)
        var sent = 0
        var dropped = 0
        var retry = 0
        outbox.prune(clock.now())
        for (item in outbox.due(clock.now(), FLUSH_BATCH)) {
            when (attempt(s, item)) {
                is Attempt.Sent -> sent++
                is Attempt.Retry -> retry++
                is Attempt.Dropped -> dropped++
            }
        }
        FlushResult(sent, dropped, retry, outbox.nextAttemptAt(clock.now()))
    }

    private sealed interface Attempt {
        data object Sent : Attempt
        data object Retry : Attempt
        data class Dropped(val code: String) : Attempt
    }

    /** Posts [initial], following the SPEC §5.2 / CLIENTS.md rules for 409, 429, 4xx and 5xx. */
    private suspend fun attempt(s: Session, initial: OutboxItem): Attempt {
        var item = initial
        repeat(MAX_RESEALS + 1) {
            val envelope = Envelopes.parse(parseJsonOrNull(item.envelopeJson)).let { (it as? Outcome.Ok)?.value }
                ?: return drop(item, "bad_shape")
            try {
                val result = s.client.postMessage(envelope)
                outbox.put(item.copy(state = OutboxState.SENT, attempts = item.attempts + 1, lastError = null, seq = result.seq))
                statusFlow.value = ConnectionStatus.IDLE
                return Attempt.Sent
            } catch (e: HubException.Http) {
                when {
                    e.code == HubErrorCode.CONFLICT && e.inactive.isNotEmpty() -> {
                        // SPEC §5.2: refetch, drop those ids, re-seal under the same msg_id.
                        directoryLock.withLock { refreshDirectoryLocked(s, checkKeyring = false) }
                        val trusted = directory.all().filter { it.trusted }.map { it.id }.toSet()
                        val remaining = item.recipients.filter { it !in e.inactive && it in trusted }
                        item = reseal(s, item, remaining) ?: return drop(item, e.code.code)
                    }
                    e.code == HubErrorCode.RATE_LIMITED && e.full.isNotEmpty() -> {
                        val remaining = item.recipients.filter { it !in e.full }
                        item = reseal(s, item, remaining) ?: return drop(item, e.code.code)
                    }
                    e.isRetryable -> return retry(item, e.code.code)
                    else -> {
                        if (e.code == HubErrorCode.UNAUTHORIZED) statusFlow.value = ConnectionStatus.REJECTED
                        return drop(item, e.code.code)
                    }
                }
            } catch (e: HubException) {
                statusFlow.value = ConnectionStatus.OFFLINE
                return retry(item, "network")
            }
        }
        return drop(item, "conflict")
    }

    /** Re-seals [item] for [recipients] under the current key and the same msg_id; null when nothing is left. */
    private suspend fun reseal(s: Session, item: OutboxItem, recipients: List<String>): OutboxItem? {
        if (recipients.isEmpty()) return null
        val contents = vault.load()
        val key = contents.currentKey ?: return null
        val original = parseJsonOrNull(item.envelopeJson)
        val opened = Envelopes.open(original, s.identity.signPub, contents.keysByKv)
        val value = (opened as? Outcome.Ok)?.value ?: return null
        val envelope = seal(s.identity, key, item.msgId, recipients, value.envelope.ttlS, value.payload)
        val next = item.copy(recipients = recipients, envelopeJson = envelope.toJsonString())
        outbox.put(next)
        return next
    }

    private fun seal(identity: Identity, key: StoredGroupKey, msgId: String, to: List<String>, ttlS: Long, payload: Payload): Envelope {
        val collapse = MessageTypes.collapseRealKey(payload.type, payload.body)
            ?.let { Collapse.value(key.key, identity.deviceId, it) }
        return Envelopes.seal(
            msgId = msgId,
            from = identity.deviceId,
            to = to,
            collapse = collapse,
            ttlS = ttlS,
            plaintext = payload.encode(),
            groupKey = key.key,
            kv = key.kv,
            signSk = identity.signSk,
            rng = rng,
        )
    }

    private suspend fun retry(item: OutboxItem, code: String): Attempt {
        val attempts = item.attempts + 1
        val delay = (RETRY_BASE_MS shl (attempts - 1).coerceAtMost(10)).coerceAtMost(RETRY_MAX_MS)
        outbox.put(item.copy(attempts = attempts, nextAttemptAt = clock.now() + delay, lastError = code))
        log.log("send_retry", code)
        return Attempt.Retry
    }

    private suspend fun drop(item: OutboxItem, code: String): Attempt {
        outbox.put(item.copy(state = OutboxState.DROPPED, attempts = item.attempts + 1, lastError = code))
        log.log("send_dropped", code)
        return Attempt.Dropped(code)
    }

    // -------------------------------------------------------------------------
    // Receiving
    // -------------------------------------------------------------------------

    override suspend fun fetchMessages(): FetchResult = receiveLock.withLock {
        val s = session() ?: return FetchResult.NotPaired
        statusFlow.value = ConnectionStatus.SYNCING
        var handled = 0
        var dropped = 0
        try {
            var pages = 0
            while (pages++ < MAX_FETCH_PAGES) {
                val page = s.client.messages(HubClient.DEFAULT_FETCH_LIMIT)
                if (page.messages.isEmpty()) break
                val outcome = processLocked(page.messages)
                handled += outcome.handled
                dropped += outcome.dropped
                // Every message was handled or dropped by a receiver rule, so all of them are acked.
                outcome.maxSeq?.let { s.client.ack(it) }
                if (!page.more) break
            }
            finishFetch()
            FetchResult.Fetched(handled, dropped)
        } catch (e: HubException) {
            FetchResult.Failed(retryable = failed(e))
        }
    }

    private suspend fun finishFetch() {
        val now = clock.now()
        seen.prune(now - Protocol.Limits.SEEN_RETENTION_MS)
        outbox.prune(now)
        statusFlow.value = ConnectionStatus.IDLE
    }

    override suspend fun process(messages: List<HubMessage>): Long? = receiveLock.withLock {
        processLocked(messages).maxSeq
    }

    private class ProcessOutcome(val maxSeq: Long?, val handled: Int, val dropped: Int)

    private suspend fun processLocked(messages: List<HubMessage>): ProcessOutcome {
        if (messages.isEmpty()) return ProcessOutcome(null, 0, 0)
        val maxSeq = messages.maxOf { it.seq }
        if (session() == null) return ProcessOutcome(null, 0, 0) // not paired: do not ack
        val sorted = messages.sortedBy { it.seq }

        val firstPass = sorted.map { it to receiveOne(it) }
        var retried: List<Pair<HubMessage, Outcome<*, ReceiveError>>> = emptyList()
        val unknownSender = firstPass.filter { (_, r) ->
            r is Outcome.Err && (r.error == ReceiveError.UNTRUSTED_SENDER || r.error == ReceiveError.UNKNOWN_KV)
        }
        if (unknownSender.isNotEmpty()) {
            // A device that just joined, or a new key: refresh once and retry those messages.
            session()?.let { s -> directoryLock.withLock { refreshDirectoryLocked(s, checkKeyring = true) } }
            retried = unknownSender.map { (m, _) -> m to receiveOne(m) }
        }
        val final = firstPass.map { (m, r) -> retried.firstOrNull { it.first === m } ?: (m to r) }
        val handled = final.count { it.second is Outcome.Ok }
        return ProcessOutcome(maxSeq, handled, final.size - handled)
    }

    /** SPEC §3.3 for one message, then dispatch. Every failure is logged by code only. */
    private suspend fun receiveOne(message: HubMessage): Outcome<*, ReceiveError> {
        val contents = vault.load()
        val identity = contents.identity ?: return Outcome.Err(ReceiveError.NOT_ADDRESSED)
        val trusted = directory.all().mapNotNull { e -> e.record?.let { e.id to B64.decode(it.signPub) } }.toMap()
        val ctx = ReceiveContext(
            selfId = identity.deviceId,
            directory = trusted,
            keys = contents.keysByKv,
            now = clock.now(),
            hasSeen = { id -> id in seen.seenAmong(listOf(id)) },
            knownTypes = knownTypes.toSet(),
            lastClipboardTs = syncState.lastClipboardTs(),
            originalRecipients = { id -> outbox.get(id)?.takeIf { it.state == OutboxState.SENT }?.recipients },
        )
        val result = Receiver.receive(message.envelope, ctx)
        when (result) {
            is Outcome.Ok -> {
                val received = result.value
                val handler = handlers.firstOrNull { it.type == received.payload.type }
                try {
                    handler?.handle(received)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.log("handler_failed", "${received.payload.type} ${e.javaClass.simpleName}")
                }
                seen.markSeen(received.envelope.msgId, clock.now())
                if (received.payload.type == MessageTypes.CLIPBOARD_SET) syncState.setLastClipboardTs(received.payload.ts)
            }
            is Outcome.Err -> log.log("receive_dropped", "${result.error.code} seq=${message.seq}")
        }
        return result
    }

    // -------------------------------------------------------------------------
    // Directory, keyring, rekey, revoke
    // -------------------------------------------------------------------------

    override suspend fun refreshDirectory(): DirectoryResult = directoryLock.withLock {
        val s = session() ?: return DirectoryResult.NotPaired
        refreshDirectoryLocked(s, checkKeyring = true)
    }

    private suspend fun refreshDirectoryLocked(s: Session, checkKeyring: Boolean): DirectoryResult {
        val devices = try {
            s.client.devices()
        } catch (e: HubException) {
            return DirectoryResult.Failed(retryable = failed(e))
        }
        val key = vault.load().currentKey ?: return DirectoryResult.NotPaired
        val previous = directory.all().associateBy { it.id }
        val entries = devices.map { d ->
            val prev = previous[d.id]
            val record: DeviceRecord? = d.record?.let { raw ->
                when (val opened = Records.open(raw, key.key, key.kv, prev?.record?.updated)) {
                    is Outcome.Ok -> opened.value.takeIf { it.id == d.id }
                    is Outcome.Err -> {
                        if (opened.error != OpenRecordError.OLD_KV) log.log("record_rejected", "${opened.error.code} ${d.id}")
                        // A rollback keeps what we already trust under this key.
                        prev?.record?.takeIf { opened.error == OpenRecordError.ROLLBACK && prev.recordKv == key.kv }
                    }
                }
            }
            DirectoryEntry(d.id, d.signPub, d.kxPub, d.createdAt, d.lastSeen, d.hasPush, record, record?.let { key.kv })
        }
        val presentIds = entries.map { it.id }.toSet()
        val removed = previous.values.filter { it.trusted && it.id !in presentIds && it.id != s.identity.deviceId }
        directory.replace(entries)
        for (gone in removed) notices.notice(NoticeText.DeviceRemoved(gone.record?.name ?: gone.id, gone.id))

        if (checkKeyring) checkKeyringLocked(s)
        val self = directory.all().firstOrNull { it.id == s.identity.deviceId }
        if (self?.record == null) publishOwnRecordLocked()
        statusFlow.value = ConnectionStatus.IDLE
        val all = directory.all()
        return DirectoryResult.Refreshed(all.count { it.trusted }, all.count { !it.trusted }, removed.map { it.id })
    }

    override suspend fun checkKeyring(): KeyringResult = directoryLock.withLock {
        val s = session() ?: return KeyringResult.NotPaired
        checkKeyringLocked(s)
    }

    private suspend fun checkKeyringLocked(s: Session): KeyringResult {
        val current = vault.load().currentKey ?: return KeyringResult.NotPaired
        val versions = try {
            s.client.keyring(have = current.kv)
        } catch (e: HubException) {
            return KeyringResult.Failed(retryable = failed(e))
        }
        if (versions.isEmpty()) return KeyringResult.UpToDate
        // Members under the previous key (SPEC §5.5 step 1), plus this device.
        val members = directory.all().mapNotNull { e -> e.record?.let { e.id to B64.decode(it.signPub) } }.toMap() +
            (s.identity.deviceId to s.identity.signPub)
        val newKvs = mutableListOf<Long>()
        val ordered = versions.sortedBy { (it as? JsonObject)?.get("kv")?.let { kv -> (kv as? JsonPrimitive)?.content?.toLongOrNull() } ?: 0 }
        for (version in ordered) {
            when (val opened = Rekey.open(version, s.identity.deviceId, s.identity.kxSk, members)) {
                is Outcome.Ok -> {
                    val now = clock.now()
                    vault.update { it.withGroupKey(opened.value.kv, opened.value.key, now).prunedGroupKeys(now) }
                    newKvs += opened.value.kv
                }
                is Outcome.Err -> log.log("keyring_rejected", opened.error.code)
            }
        }
        if (newKvs.isEmpty()) return KeyringResult.UpToDate
        // Step 4: republish under K', then re-open everyone's records under the new key.
        publishOwnRecordLocked()
        refreshDirectoryLocked(s, checkKeyring = false)
        return KeyringResult.Updated(newKvs)
    }

    override suspend fun rekey(excluding: Set<String>): RekeyResult = directoryLock.withLock {
        val s = session() ?: return RekeyResult.NotPaired
        rekeyLocked(s, excluding)
    }

    private suspend fun rekeyLocked(s: Session, excluding: Set<String>): RekeyResult {
        val current = vault.load().currentKey ?: return RekeyResult.NotPaired
        val members = directory.all()
            .filter { it.trusted && it.id !in excluding && it.id != s.identity.deviceId }
            .map { RekeyMember(it.id, B64.decode(it.record!!.kxPub)) } +
            RekeyMember(s.identity.deviceId, s.identity.kxPub)
        val newKey = rng.bytes(Protocol.KEY_LENGTH)
        val kv = current.kv + 1
        val version = Rekey.build(s.identity, kv, newKey, members, rng = rng)
        try {
            s.client.putKeyring(version)
        } catch (e: HubException.Http) {
            if (e.code == HubErrorCode.CONFLICT) {
                checkKeyringLocked(s)
                return RekeyResult.Conflict
            }
            return RekeyResult.Failed(retryable = failed(e))
        } catch (e: HubException) {
            return RekeyResult.Failed(retryable = failed(e))
        }
        val now = clock.now()
        vault.update { it.withGroupKey(kv, newKey, now).prunedGroupKeys(now) }
        publishOwnRecordLocked()
        log.log("rekeyed", "kv=$kv")
        return RekeyResult.Rekeyed(kv)
    }

    override suspend fun revoke(deviceId: String): RevokeResult = directoryLock.withLock {
        val s = session() ?: return RevokeResult.NotPaired
        val self = deviceId == s.identity.deviceId
        try {
            s.client.revoke(if (self) HubClient.SELF else deviceId)
        } catch (e: HubException) {
            return RevokeResult.Failed(retryable = failed(e))
        }
        if (self) {
            wipeLocked()
            return RevokeResult.RevokedSelf
        }
        refreshDirectoryLocked(s, checkKeyring = true)
        var result = rekeyLocked(s, excluding = setOf(deviceId))
        if (result == RekeyResult.Conflict) {
            // Someone rekeyed concurrently; rekey once more so the revoked device is surely excluded.
            result = rekeyLocked(s, excluding = setOf(deviceId))
        }
        return if (result is RekeyResult.Rekeyed) RevokeResult.Revoked else RevokeResult.RevokedRekeyPending
    }

    override suspend fun publishOwnRecord(): Boolean = directoryLock.withLock { publishOwnRecordLocked() }

    override suspend fun setReceiveRule(type: String, rule: ReceiveRule): Boolean = directoryLock.withLock {
        publishOwnRecordLocked(receiveChange = type to rule)
    }

    private suspend fun publishOwnRecordLocked(receiveChange: Pair<String, ReceiveRule>? = null): Boolean {
        val s = session() ?: return false
        val profile = profileProvider.profile()
        val entries = directory.all()
        val previous = entries.firstOrNull { it.id == s.identity.deviceId }?.record
        val record = DeviceRecord(
            id = s.identity.deviceId,
            name = profile.name,
            kind = profile.kind,
            platform = profile.platform,
            appVersion = profile.appVersion,
            signPub = B64.encode(s.identity.signPub),
            kxPub = B64.encode(s.identity.kxPub),
            caps = knownTypes,
            receive = (profile.receive ?: previous?.receive ?: Routing.defaultReceive(profile.kind))
                .let { base -> if (receiveChange == null) base else base + receiveChange },
            updated = maxOf(clock.now(), (previous?.updated ?: 0) + 1),
        )
        val sealed = Records.seal(record, s.key.key, s.key.kv, s.identity.signSk, rng = rng)
        return try {
            s.client.putRecord(sealed)
            directory.replace(
                entries.map { if (it.id == record.id) it.copy(record = record, recordKv = s.key.kv) else it },
            )
            true
        } catch (e: HubException) {
            failed(e)
            false
        }
    }

    override suspend fun onRevoked() = directoryLock.withLock { wipeLocked() }

    private suspend fun wipeLocked() {
        vault.wipe()
        outbox.clear()
        seen.clear()
        directory.clear()
        localStateWiper.wipe()
        statusFlow.value = ConnectionStatus.NOT_SET_UP
        log.log("wiped", null)
    }

    /** Records the failure in [status]; returns whether it is worth retrying. */
    private fun failed(e: HubException): Boolean = when (e) {
        is HubException.Http -> {
            if (e.code == HubErrorCode.UNAUTHORIZED || e.code == HubErrorCode.FORBIDDEN) {
                statusFlow.value = ConnectionStatus.REJECTED
            } else if (e.isRetryable) {
                statusFlow.value = ConnectionStatus.OFFLINE
            }
            log.log("hub_error", "${e.status} ${e.code.code}")
            e.isRetryable
        }
        else -> {
            statusFlow.value = ConnectionStatus.OFFLINE
            log.log("hub_unreachable", e.javaClass.simpleName)
            true
        }
    }

    private companion object {
        const val FLUSH_BATCH = 50
        const val MAX_FETCH_PAGES = 50
        const val MAX_RESEALS = 3
        const val RETRY_BASE_MS = 15_000L
        const val RETRY_MAX_MS = 30 * 60_000L
    }
}
