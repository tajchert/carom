package com.mtajchert.carom.app.core.testing.sync

import com.mtajchert.carom.app.core.data.sync.CaromSync
import com.mtajchert.carom.app.core.data.sync.DirectoryResult
import com.mtajchert.carom.app.core.data.sync.FetchResult
import com.mtajchert.carom.app.core.data.sync.FlushResult
import com.mtajchert.carom.app.core.data.sync.KeyringResult
import com.mtajchert.carom.app.core.data.sync.RekeyResult
import com.mtajchert.carom.app.core.data.sync.RevokeResult
import com.mtajchert.carom.app.core.data.sync.SendResult
import com.mtajchert.carom.app.core.data.work.SyncWorkScheduler
import com.mtajchert.carom.app.core.hub.HubMessage
import com.mtajchert.carom.app.core.model.ConnectionStatus
import com.mtajchert.carom.app.core.protocol.ReceiveRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject

/** Scriptable [CaromSync] for ViewModel and use case tests. Records what was sent. */
class FakeCaromSync : CaromSync {
    override val status = MutableStateFlow(ConnectionStatus.IDLE)

    data class Sent(val type: String, val body: JsonObject, val replyTo: String?, val onlyTo: Set<String>?)

    val sent = mutableListOf<Sent>()
    var nextSendResult: SendResult = SendResult.Sent("01a1268a-e800-7000-8000-000000000000")
    var revoked = mutableListOf<String>()
    var nextRevokeResult: RevokeResult = RevokeResult.Revoked

    /** Messages handed to [process], in order. */
    val processed = mutableListOf<HubMessage>()
    var fetches = 0
    var flushes = 0
    var directoryRefreshes = 0
    var published = 0
    var wipes = 0

    override suspend fun send(type: String, body: JsonObject, replyTo: String?, replyToSender: String?, onlyTo: Set<String>?): SendResult {
        sent += Sent(type, body, replyTo, onlyTo)
        return nextSendResult
    }

    override suspend fun flushOutbox(): FlushResult {
        flushes++
        return FlushResult(0, 0, 0, null)
    }

    override suspend fun fetchMessages(): FetchResult {
        fetches++
        return FetchResult.Fetched(0, 0)
    }

    override suspend fun process(messages: List<HubMessage>): Long? {
        processed += messages
        return messages.maxOfOrNull { it.seq }
    }

    override suspend fun refreshDirectory(): DirectoryResult {
        directoryRefreshes++
        return DirectoryResult.Refreshed(0, 0, emptyList())
    }

    override suspend fun checkKeyring(): KeyringResult = KeyringResult.UpToDate
    override suspend fun rekey(excluding: Set<String>): RekeyResult = RekeyResult.Rekeyed(2)
    override suspend fun revoke(deviceId: String): RevokeResult {
        revoked += deviceId
        return nextRevokeResult
    }

    override suspend fun publishOwnRecord(): Boolean {
        published++
        return true
    }

    val receiveRules = mutableMapOf<String, ReceiveRule>()
    override suspend fun setReceiveRule(type: String, rule: ReceiveRule): Boolean {
        receiveRules[type] = rule
        return true
    }
    override suspend fun onRevoked() {
        wipes++
        status.value = ConnectionStatus.NOT_SET_UP
    }
}

class FakeSyncWorkScheduler : SyncWorkScheduler {
    var flushes = 0
    val fetches = mutableListOf<Boolean>()
    var periodic: Boolean? = null
    var cancelled = 0

    override fun scheduleOutboxFlush(delayMs: Long) {
        flushes++
    }

    /** Records each request; true means expedited. */
    override fun scheduleFetch(expedited: Boolean) {
        fetches += expedited
    }

    override fun setPeriodicFetch(enabled: Boolean) {
        periodic = enabled
    }

    override fun cancelAll() {
        cancelled++
        periodic = null
    }
}
