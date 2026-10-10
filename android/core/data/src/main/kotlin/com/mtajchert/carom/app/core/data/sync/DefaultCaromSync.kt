package com.mtajchert.carom.app.core.data.sync

import com.mtajchert.carom.app.core.hub.HubClient
import com.mtajchert.carom.app.core.hub.HubDevice
import com.mtajchert.carom.app.core.hub.HubErrorCode
import com.mtajchert.carom.app.core.hub.HubException
import com.mtajchert.carom.app.core.hub.HubMessage
import com.mtajchert.carom.app.core.model.ConnectionStatus
import com.mtajchert.carom.app.core.protocol.B64
import com.mtajchert.carom.app.core.protocol.Collapse
import com.mtajchert.carom.app.core.protocol.DeviceIds
import com.mtajchert.carom.app.core.protocol.DeviceRecord
import com.mtajchert.carom.app.core.protocol.Envelope
import com.mtajchert.carom.app.core.protocol.Envelopes
import com.mtajchert.carom.app.core.protocol.Identity
import com.mtajchert.carom.app.core.protocol.MessageBodies
import com.mtajchert.carom.app.core.protocol.MessageTypes
import com.mtajchert.carom.app.core.protocol.OpenRecordError
import com.mtajchert.carom.app.core.protocol.Outcome
import com.mtajchert.carom.app.core.protocol.Payload
import com.mtajchert.carom.app.core.protocol.Protocol
import com.mtajchert.carom.app.core.protocol.ReceiveContext
import com.mtajchert.carom.app.core.protocol.ReceiveError
import com.mtajchert.carom.app.core.protocol.Received
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
 *
 * SPEC behaviours implemented here, beyond plain send/receive:
 * - §3.3: one directory + keyring refresh before dropping for `unknown_kv` or an untrusted sender.
 * - §5.3: transitional trust for `kv − 1` records of devices boxed in the newest verified version.
 * - §5.4: removal notices ("<revoker> removed <device>", or "removed (no rekey)" after 2 minutes)
 *   and the `401` latch: syncing stops until the user pairs again ([onRevoked] wipes).
 * - §5.5: keyring versions verified as a chain, in order; our record republished under each new key.
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
    private val wipeHooks: Set<WipeHook> = emptySet(),
    private val sentListener: SentListener = SentListener.NONE,
) : CaromSync {

    private val outboxLock = Mutex()
    private val receiveLock = Mutex()
    private val directoryLock = Mutex()

    private val statusFlow = MutableStateFlow(ConnectionStatus.IDLE)
    override val status: StateFlow<ConnectionStatus> = statusFlow.asStateFlow()

    /** Devices this device revoked in this process; their removal needs no notice here. */
    private val revokedHere = mutableSetOf<String>()

    /** Types this device handles; also its `caps`. Ordered like [MessageTypes.ALL] for stable records. */
    private val knownTypes: List<String> = handlers.map { it.type }.distinct().sortedBy { type ->
        MessageTypes.ALL.indexOf(type).let { if (it < 0) Int.MAX_VALUE else it }
    }

    /** Identity, current key and client, or null when this device is not paired. */
    private class Session(val contents: VaultContents, val identity: Identity, val key: StoredGroupKey, val client: HubClient)

    /** The session, or null when this device is not paired or the Hub refused it (SPEC §5.4 `401`). */
    private suspend fun session(): Session? {
        val contents = vault.load()
        val identity = contents.identity ?: return null
        val key = contents.currentKey ?: return null
        if (contents.deviceToken == null) return null
        if (syncState.isRejected()) {
            statusFlow.value = ConnectionStatus.REJECTED
            return null
        }
        val client = hubProvider.client() ?: return null
        return Session(contents, identity, key, client)
    }

    /** True when [session] returned null because of the `401` latch rather than a missing pairing. */
    private suspend fun rejected(): Boolean = vault.load().isComplete && syncState.isRejected()

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
            is Attempt.Sent -> {
                sentListener.onSent()
                SendResult.Sent(msgId)
            }
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
        if (sent > 0) sentListener.onSent()
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
                    e.isRetryable -> {
                        failed(e)
                        return retry(item, e.code.code)
                    }
                    else -> {
                        failed(e)
                        return drop(item, e.code.code)
                    }
                }
            } catch (e: HubException) {
                failed(e)
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
        val s = session() ?: return if (rejected()) FetchResult.Failed(retryable = false) else FetchResult.NotPaired
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
            finishFetch(s)
            FetchResult.Fetched(handled, dropped)
        } catch (e: HubException) {
            FetchResult.Failed(retryable = failed(e))
        }
    }

    private suspend fun finishFetch(s: Session) {
        val now = clock.now()
        seen.prune(now - Protocol.Limits.SEEN_RETENTION_MS)
        outbox.prune(now)
        syncState.setLastSyncAt(now)
        statusFlow.value = ConnectionStatus.IDLE
        // A removal seen earlier may now be past its grace period: check the keyring once, then flag it.
        if (syncState.pendingRemovals().any { now - it.detectedAt >= REMOVAL_GRACE_MS }) {
            directoryLock.withLock {
                checkKeyringLocked(s)
                flagUnexplainedRemovals()
            }
        }
    }

    override suspend fun process(messages: List<HubMessage>): Long? = receiveLock.withLock {
        val outcome = processLocked(messages)
        if (outcome.maxSeq != null) syncState.setLastSyncAt(clock.now())
        outcome.maxSeq
    }

    private class ProcessOutcome(val maxSeq: Long?, val handled: Int, val dropped: Int)

    private suspend fun processLocked(messages: List<HubMessage>): ProcessOutcome {
        if (messages.isEmpty()) return ProcessOutcome(null, 0, 0)
        val maxSeq = messages.maxOf { it.seq }
        if (session() == null) return ProcessOutcome(null, 0, 0) // not paired or refused: do not ack
        val sorted = messages.sortedBy { it.seq }

        val firstPass = sorted.map { it to receiveOne(it) }
        var retried: List<Pair<HubMessage, Outcome<*, ReceiveError>>> = emptyList()
        val unknownSender = firstPass.filter { (_, r) -> r is Outcome.Err && r.error in REFRESH_FIRST }
        if (unknownSender.isNotEmpty()) {
            // SPEC §3.3 rule 4: refresh the directory and the keyring once, then re-check.
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
        val result = Receiver.receive(message.envelope, ctx).let { r ->
            if (r is Outcome.Ok && !dismissMatchesOriginal(r.value, identity, contents.keysByKv)) {
                Outcome.Err(ReceiveError.REPLY_NOT_ALLOWED)
            } else {
                r
            }
        }
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

    /**
     * SPEC §3.3 rule 7, second half: a `notification.dismiss@1` must name the `key` of the
     * `notification.posted@1` it replies to. The original is reopened from our outbox (sealed by us,
     * kept until it expires), so a recipient can only dismiss the notification it was sent.
     */
    private suspend fun dismissMatchesOriginal(received: Received, identity: Identity, keys: Map<Long, ByteArray>): Boolean {
        if (received.payload.type != MessageTypes.NOTIFICATION_DISMISS) return true
        val replyTo = received.payload.replyTo ?: return false
        val original = outbox.get(replyTo)?.takeIf { it.state == OutboxState.SENT && it.type == MessageTypes.NOTIFICATION_POSTED }
            ?: return false
        val opened = (Envelopes.open(parseJsonOrNull(original.envelopeJson), identity.signPub, keys) as? Outcome.Ok)?.value ?: return false
        if (opened.payload.type != MessageTypes.NOTIFICATION_POSTED) return false
        val postedKey = MessageBodies.fromJson(MessageBodies.NotificationPosted.serializer(), opened.payload.body)?.key ?: return false
        val dismissKey = MessageBodies.fromJson(MessageBodies.NotificationDismiss.serializer(), received.payload.body)?.key ?: return false
        return postedKey == dismissKey
    }

    // -------------------------------------------------------------------------
    // Directory and trust (SPEC §5.3, §5.4)
    // -------------------------------------------------------------------------

    override suspend fun refreshDirectory(): DirectoryResult = directoryLock.withLock {
        val s = session() ?: return if (rejected()) DirectoryResult.Failed(retryable = false) else DirectoryResult.NotPaired
        refreshDirectoryLocked(s, checkKeyring = true)
    }

    private suspend fun refreshDirectoryLocked(s: Session, checkKeyring: Boolean): DirectoryResult {
        val devices = try {
            s.client.devices()
        } catch (e: HubException) {
            return DirectoryResult.Failed(retryable = failed(e))
        }
        val contents = vault.load()
        val key = contents.currentKey ?: return DirectoryResult.NotPaired
        val previous = directory.all()
        val previousById = previous.associateBy { it.id }
        suspend fun open(): List<DirectoryEntry> {
            val trust = TrustContext(
                key = key,
                previousKey = contents.keysByKv[key.kv - 1],
                // Transitional trust only follows the newest verified version, and only for the current key.
                boxedInCurrent = syncState.verifiedKeyring()?.takeIf { it.kv == key.kv }?.members.orEmpty(),
            )
            return devices.map { d ->
                val prev = previousById[d.id]
                val (record, kv) = openRecord(d, trust, prev) ?: (null to null)
                DirectoryEntry(d.id, d.signPub, d.kxPub, d.createdAt, d.lastSeen, record, kv)
            }
        }
        var entries = open()
        // SPEC §5.3: a device that joined through a welcome holds the newest key but not the box
        // list of the version that made it; fetch and verify it so transitional trust applies.
        if (checkKeyring && adoptCurrentKeyringVersion(s, key, entries)) entries = open()
        val presentIds = entries.map { it.id }.toSet()
        val removed = previous.filter { it.trusted && it.id !in presentIds && it.id != s.identity.deviceId }
        directory.replace(entries)
        if (removed.isNotEmpty()) noteRemovals(removed, previous)

        // The pre-refresh cache still knows the keys of devices that rekeyed and then left.
        if (checkKeyring) checkKeyringLocked(s, previous)
        flagUnexplainedRemovals()
        retryPendingRekey(s)
        val currentKv = vault.load().currentKey?.kv
        val self = directory.all().firstOrNull { it.id == s.identity.deviceId }
        if (self?.record == null || self.recordKv != currentKv) publishOwnRecordLocked()
        statusFlow.value = ConnectionStatus.IDLE
        val all = directory.all()
        return DirectoryResult.Refreshed(all.count { it.trusted }, all.count { !it.trusted }, removed.map { it.id })
    }

    /**
     * SPEC §5.3, joiner: `GET /v1/keyring?have=<kv − 1>` for the version of our current key, whose
     * signature is verified with the `sign_pub` of a trusted member (or our own). Our key came in
     * the welcome, so that version usually has no box for us; its box list is what we need.
     * Returns true when it became the newest verified version.
     */
    private suspend fun adoptCurrentKeyringVersion(s: Session, key: StoredGroupKey, entries: List<DirectoryEntry>): Boolean {
        if (key.kv < 2 || syncState.verifiedKeyring()?.kv == key.kv) return false
        val raw = try {
            s.client.keyring(have = key.kv - 1)
        } catch (e: HubException) {
            failed(e)
            return false
        }
        val json = raw.firstOrNull { Rekey.parse(it)?.kv == key.kv } ?: return false
        val version = Rekey.parse(json) ?: return false
        val signer = if (version.by == s.identity.deviceId) {
            s.identity.signPub
        } else {
            entries.firstOrNull { it.id == version.by && it.trusted && it.recordKv == key.kv }
                ?.let { B64.decodeOrNull(it.record!!.signPub) }
        } ?: run {
            log.log("keyring_rejected", "untrusted_signer kv=${version.kv}")
            return false
        }
        if (!DeviceIds.matches(version.by, signer) || !Rekey.verifySignature(version, signer)) {
            log.log("keyring_rejected", "bad_signature kv=${version.kv}")
            return false
        }
        // When the version does box this device, it must hold exactly the key we already have.
        if (s.identity.deviceId in version.boxes) {
            val opened = Rekey.open(json, s.identity.deviceId, s.identity.kxSk, mapOf(version.by to signer))
            if ((opened as? Outcome.Ok)?.value?.key?.contentEquals(key.key) != true) {
                log.log("keyring_rejected", "key_mismatch kv=${version.kv}")
                return false
            }
        }
        syncState.setVerifiedKeyring(VerifiedKeyring(version.kv, version.by, version.boxes.keys))
        log.log("keyring_adopted", "kv=${version.kv}")
        return true
    }

    private class TrustContext(val key: StoredGroupKey, val previousKey: ByteArray?, val boxedInCurrent: Set<String>)

    /**
     * Opens [d]'s record under the current key, or under `kv − 1` when the newest verified keyring
     * version boxed the device (transitional trust). Returns the record and the kv it was sealed under.
     */
    private fun openRecord(d: HubDevice, trust: TrustContext, prev: DirectoryEntry?): Pair<DeviceRecord, Long>? {
        val raw = d.record ?: return null
        val lastUpdated = prev?.record?.updated
        val previousKv = trust.key.kv - 1
        val transitional = trust.previousKey != null && d.id in trust.boxedInCurrent
        // On a rollback we keep what we already trust, if it is still trusted under the current key.
        val keep: Pair<DeviceRecord, Long>? = prev?.record?.let { record ->
            prev.recordKv?.takeIf { it == trust.key.kv || (it == previousKv && transitional) }?.let { record to it }
        }
        val current = Records.open(raw, trust.key.key, trust.key.kv, lastUpdated)
        if (current is Outcome.Ok) return current.value.takeIf { it.id == d.id }?.let { it to trust.key.kv }
        val error = (current as Outcome.Err).error
        if (error == OpenRecordError.ROLLBACK) return keep
        if (error == OpenRecordError.OLD_KV && transitional) {
            when (val old = Records.open(raw, trust.previousKey!!, previousKv, lastUpdated)) {
                is Outcome.Ok -> return old.value.takeIf { it.id == d.id }?.let { it to previousKv }
                is Outcome.Err -> {
                    if (old.error == OpenRecordError.ROLLBACK) return keep
                    log.log("record_rejected", "${old.error.code} ${d.id}")
                }
            }
            return null
        }
        if (error != OpenRecordError.OLD_KV) log.log("record_rejected", "${error.code} ${d.id}")
        return null
    }

    /**
     * Trusted devices that vanished from the directory. Explained right away when the newest verified
     * keyring version already dropped them; otherwise kept pending for [REMOVAL_GRACE_MS].
     */
    private suspend fun noteRemovals(removed: List<DirectoryEntry>, previous: List<DirectoryEntry>) {
        val keyring = syncState.verifiedKeyring()
        val pending = syncState.pendingRemovals().toMutableList()
        val now = clock.now()
        for (gone in removed) {
            if (gone.id in revokedHere) continue
            val name = gone.record?.name ?: DeviceIds.fingerprint(gone.id)
            if (keyring != null && gone.id !in keyring.members && keyring.kv > (gone.recordKv ?: 0)) {
                notices.notice(NoticeText.DeviceRemoved(name, gone.id, nameOf(keyring.by, previous), keyring.by))
            } else if (pending.none { it.deviceId == gone.id }) {
                pending += PendingRemoval(gone.id, name, now)
            }
        }
        syncState.setPendingRemovals(pending)
    }

    /** A newly verified version explains every pending removal it does not box. */
    private suspend fun explainRemovals(version: VerifiedKeyring, signers: List<DirectoryEntry>) {
        val pending = syncState.pendingRemovals()
        if (pending.isEmpty()) return
        val (explained, still) = pending.partition { it.deviceId !in version.members }
        for (p in explained) {
            notices.notice(NoticeText.DeviceRemoved(p.deviceName, p.deviceId, nameOf(version.by, signers), version.by))
        }
        syncState.setPendingRemovals(still)
    }

    /** SPEC §5.4: a device that disappeared with no rekey within 2 minutes is flagged. */
    private suspend fun flagUnexplainedRemovals() {
        val pending = syncState.pendingRemovals()
        if (pending.isEmpty()) return
        val now = clock.now()
        val (late, waiting) = pending.partition { now - it.detectedAt >= REMOVAL_GRACE_MS }
        for (p in late) notices.notice(NoticeText.DeviceRemovedNoRekey(p.deviceName, p.deviceId))
        if (late.isNotEmpty()) syncState.setPendingRemovals(waiting)
    }

    private suspend fun nameOf(id: String, extra: List<DirectoryEntry>): String {
        vault.load().identity?.takeIf { it.deviceId == id }?.let { return profileProvider.profile().name }
        return (directory.all() + extra).firstNotNullOfOrNull { e -> e.record?.name?.takeIf { e.id == id } }
            ?: DeviceIds.fingerprint(id)
    }

    // -------------------------------------------------------------------------
    // Keyring and rekey (SPEC §5.5)
    // -------------------------------------------------------------------------

    override suspend fun checkKeyring(): KeyringResult = directoryLock.withLock {
        val s = session() ?: return if (rejected()) KeyringResult.Failed(retryable = false) else KeyringResult.NotPaired
        checkKeyringLocked(s)
    }

    /**
     * Fetches every version newer than our key and verifies them as a chain, in order: the writer of
     * kv n+1 must be boxed in version n (for the first new version, a trusted member under our key).
     * Stops at the first version that fails; later versions cannot be verified without it.
     *
     * @param extraSigners directory entries from before the latest refresh, whose `sign_pub` may be
     *   needed for a writer that has since left.
     */
    private suspend fun checkKeyringLocked(s: Session, extraSigners: List<DirectoryEntry> = emptyList()): KeyringResult {
        val current = vault.load().currentKey ?: return KeyringResult.NotPaired
        val raw = try {
            s.client.keyring(have = current.kv)
        } catch (e: HubException) {
            return KeyringResult.Failed(retryable = failed(e))
        }
        if (raw.isEmpty()) return KeyringResult.UpToDate
        val versions = raw.mapNotNull { json -> Rekey.parse(json)?.let { it to json } }.sortedBy { it.first.kv }
        val entries = directory.all()
        val signers = extraSigners + entries
        // Any (id, sign_pub) pair that matches device_id(sign_pub) identifies its device (SPEC §2.2).
        val signPubs = HashMap<String, ByteArray>()
        for (e in signers) {
            val pub = B64.decodeOrNull(e.record?.signPub ?: e.signPub) ?: continue
            if (DeviceIds.matches(e.id, pub)) signPubs[e.id] = pub
        }
        signPubs[s.identity.deviceId] = s.identity.signPub

        var allowed: Set<String> = syncState.verifiedKeyring()?.takeIf { it.kv == current.kv }?.members
            ?: (signers.filter { it.trusted && (it.recordKv ?: 0) >= current.kv - 1 }.map { it.id } + s.identity.deviceId).toSet()
        var expected = current.kv + 1
        val newKvs = mutableListOf<Long>()
        for ((version, json) in versions) {
            if (version.kv < expected) continue
            if (version.kv != expected) {
                log.log("keyring_rejected", "gap kv=${version.kv}")
                break
            }
            val signer = signPubs[version.by]?.takeIf { version.by in allowed }
            if (signer == null) {
                log.log("keyring_rejected", "untrusted_signer kv=${version.kv}")
                break
            }
            when (val opened = Rekey.open(json, s.identity.deviceId, s.identity.kxSk, mapOf(version.by to signer))) {
                is Outcome.Ok -> {
                    val now = clock.now()
                    vault.update { it.withGroupKey(opened.value.kv, opened.value.key, now).prunedGroupKeys(now) }
                    val verified = VerifiedKeyring(version.kv, version.by, version.boxes.keys)
                    syncState.setVerifiedKeyring(verified)
                    explainRemovals(verified, signers)
                    newKvs += version.kv
                }
                is Outcome.Err -> {
                    log.log("keyring_rejected", "${opened.error.code} kv=${version.kv}")
                    break
                }
            }
            allowed = version.boxes.keys
            expected++
        }
        if (newKvs.isEmpty()) return KeyringResult.UpToDate
        log.log("keyring_updated", "kv=${newKvs.last()}")
        // Step 4: republish under the newest key, then re-open everyone's records under it.
        publishOwnRecordLocked()
        refreshDirectoryLocked(s, checkKeyring = false)
        return KeyringResult.Updated(newKvs)
    }

    override suspend fun rekey(excluding: Set<String>): RekeyResult = directoryLock.withLock {
        val s = session() ?: return if (rejected()) RekeyResult.Failed(retryable = false) else RekeyResult.NotPaired
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
        val verified = VerifiedKeyring(kv, s.identity.deviceId, members.map { it.id }.toSet())
        syncState.setVerifiedKeyring(verified)
        explainRemovals(verified, emptyList())
        publishOwnRecordLocked()
        log.log("rekeyed", "kv=$kv")
        return RekeyResult.Rekeyed(kv)
    }

    /**
     * "Leave hub" (SPEC §5.4): boxes a new key for every other trusted member, but not for this
     * device, and keeps nothing locally. A lost race (409) learns the winning key and tries again.
     * With no other member there is nobody to protect, so nothing is written ([RekeyResult.NotPaired]).
     */
    private suspend fun rekeyWithoutSelf(s: Session): RekeyResult {
        repeat(MAX_RESEALS) {
            val current = vault.load().currentKey ?: return RekeyResult.NotPaired
            val members = directory.all()
                .filter { it.trusted && it.id != s.identity.deviceId }
                .map { RekeyMember(it.id, B64.decode(it.record!!.kxPub)) }
            if (members.isEmpty()) return RekeyResult.NotPaired
            val kv = current.kv + 1
            val version = Rekey.build(s.identity, kv, rng.bytes(Protocol.KEY_LENGTH), members, rng = rng, leaving = true)
            try {
                s.client.putKeyring(version)
                log.log("rekeyed_without_self", "kv=$kv")
                return RekeyResult.Rekeyed(kv)
            } catch (e: HubException.Http) {
                if (e.code != HubErrorCode.CONFLICT) return RekeyResult.Failed(retryable = failed(e))
                // Someone else rekeyed first: learn their key (and members), then rekey on top of it.
                checkKeyringLocked(s)
            } catch (e: HubException) {
                return RekeyResult.Failed(retryable = failed(e))
            }
        }
        return RekeyResult.Conflict
    }

    /** Retries a rekey this device owes after a revocation, until a newer key exists without those devices. */
    private suspend fun retryPendingRekey(s: Session) {
        val pending = syncState.pendingRekey() ?: return
        val current = vault.load().currentKey ?: return
        val newest = syncState.verifiedKeyring()
        if (current.kv > pending.kvAtRevoke && newest?.kv == current.kv && pending.excluding.none { it in newest.members }) {
            syncState.setPendingRekey(null)
            return
        }
        when (rekeyLocked(s, pending.excluding)) {
            is RekeyResult.Rekeyed -> syncState.setPendingRekey(null)
            else -> Unit // Conflict: re-checked on the next refresh against the version that won.
        }
    }

    override suspend fun revoke(deviceId: String): RevokeResult = directoryLock.withLock {
        val selfId = vault.load().identity?.deviceId ?: return RevokeResult.NotPaired
        val self = deviceId == selfId || deviceId == HubClient.SELF
        if (rejected()) {
            // The Hub no longer accepts us (SPEC §5.4); only leaving is still possible, locally.
            if (!self) return RevokeResult.Failed(retryable = false)
            wipeLocked(runHooks = true)
            return RevokeResult.RevokedSelf
        }
        val s = session() ?: return RevokeResult.NotPaired
        if (self) {
            // SPEC §5.4: a device leaving on its own first rekeys without itself, so the others
            // keep a key it never held, and only then deletes itself.
            when (val rekey = rekeyWithoutSelf(s)) {
                is RekeyResult.Rekeyed, RekeyResult.NotPaired -> Unit
                is RekeyResult.Failed -> return RevokeResult.Failed(rekey.retryable)
                RekeyResult.Conflict -> return RevokeResult.Failed(retryable = true)
            }
            // Hooks that talk to the Hub run while the token still works.
            runWipeHooks(s.client)
            try {
                s.client.revoke(HubClient.SELF)
            } catch (e: HubException) {
                if (!(e is HubException.Http && e.code == HubErrorCode.UNAUTHORIZED)) {
                    return RevokeResult.Failed(retryable = failed(e))
                }
            }
            wipeLocked(runHooks = false)
            return RevokeResult.RevokedSelf
        }
        try {
            s.client.revoke(deviceId)
        } catch (e: HubException) {
            return RevokeResult.Failed(retryable = failed(e))
        }
        revokedHere += deviceId
        val pending = syncState.pendingRekey()
        syncState.setPendingRekey(
            PendingRekey(
                kvAtRevoke = minOf(pending?.kvAtRevoke ?: Long.MAX_VALUE, s.key.kv),
                excluding = pending?.excluding.orEmpty() + deviceId,
            ),
        )
        // The refresh performs the pending rekey. If another device won the race (409) with a version
        // that still boxed the revoked device, the refresh that follows learning it rekeys again.
        refreshDirectoryLocked(s, checkKeyring = true)
        return if (syncState.pendingRekey() == null) RevokeResult.Revoked else RevokeResult.RevokedRekeyPending
    }

    override suspend fun publishOwnRecord(): Boolean = directoryLock.withLock { publishOwnRecordLocked() }

    private suspend fun publishOwnRecordLocked(): Boolean {
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
            receive = profile.receive ?: previous?.receive ?: Routing.defaultReceive(profile.kind),
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

    override suspend fun onRevoked() = directoryLock.withLock { wipeLocked(runHooks = true) }

    private suspend fun runWipeHooks(client: HubClient?) {
        for (hook in wipeHooks) {
            try {
                hook.beforeWipe(client)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.log("wipe_hook_failed", e.javaClass.simpleName)
            }
        }
    }

    private suspend fun wipeLocked(runHooks: Boolean) {
        if (runHooks) runWipeHooks(hubProvider.client())
        vault.wipe()
        outbox.clear()
        seen.clear()
        directory.clear()
        syncState.clear()
        localStateWiper.wipe()
        revokedHere.clear()
        statusFlow.value = ConnectionStatus.NOT_SET_UP
        log.log("wiped", null)
    }

    /** Records the failure in [status]; returns whether it is worth retrying. */
    private suspend fun failed(e: HubException): Boolean = when (e) {
        is HubException.Http -> {
            if (e.code == HubErrorCode.UNAUTHORIZED) {
                // SPEC §5.4: revoked, or the Hub was reset. Stop syncing until the user pairs again.
                syncState.setRejected(true)
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

    companion object {
        private const val FLUSH_BATCH = 50
        private const val MAX_FETCH_PAGES = 50
        private const val MAX_RESEALS = 3
        private const val RETRY_BASE_MS = 15_000L
        private const val RETRY_MAX_MS = 30 * 60_000L

        /** SPEC §5.4: how long a vanished device may wait for the rekey that explains it. */
        const val REMOVAL_GRACE_MS = 2 * 60_000L

        private val REFRESH_FIRST = setOf(ReceiveError.UNTRUSTED_SENDER, ReceiveError.UNKNOWN_KV)
    }
}
