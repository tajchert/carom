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
import com.mtajchert.carom.app.core.protocol.MessageBodies
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject

/** Scriptable [CaromSync] for ViewModel and use case tests. Records what was sent. */
class FakeCaromSync : CaromSync {
    override val status = MutableStateFlow(ConnectionStatus.IDLE)

    data class Sent(
        val type: String,
        val body: JsonObject,
        val replyTo: String?,
        val onlyTo: Set<String>?,
        val replyToSender: String? = null,
        val collapse: Boolean = true,
        val ttlS: Long? = null,
    )

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

    override suspend fun send(type: String, body: JsonObject, replyTo: String?, replyToSender: String?, onlyTo: Set<String>?, collapse: Boolean, ttlS: Long?): SendResult {
        sent += Sent(type, body, replyTo, onlyTo, replyToSender, collapse, ttlS)
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

    class Upload(
        val plain: ByteArray,
        val mime: String,
        val w: Int?,
        val h: Int?,
        val convergent: Boolean,
        val ttlS: Long,
        val msgId: String?,
    )

    /** Every [uploadBlob] call, in order. */
    val uploads = mutableListOf<Upload>()

    /** What [uploadBlob] returns; null simulates a failed upload. */
    var uploadResult: (Upload) -> MessageBodies.BlobRef? = { u ->
        MessageBodies.BlobRef(
            id = "blob-${uploads.size}",
            key = "fake-key",
            mime = u.mime,
            size = u.plain.size.toLong(),
            hash = "fake-hash",
            w = u.w,
            h = u.h,
        )
    }

    override suspend fun uploadBlob(
        plain: ByteArray,
        mime: String,
        w: Int?,
        h: Int?,
        convergent: Boolean,
        ttlS: Long,
        msgId: String?,
    ): MessageBodies.BlobRef? {
        val upload = Upload(plain, mime, w, h, convergent, ttlS, msgId)
        uploads += upload
        return uploadResult(upload)
    }

    override suspend fun onRevoked() {
        wipes++
        status.value = ConnectionStatus.NOT_SET_UP
    }
}

class FakeSyncWorkScheduler : SyncWorkScheduler {
    var flushes = 0
    var fetches = 0
    var cancelled = 0

    override fun scheduleOutboxFlush(delayMs: Long) {
        flushes++
    }

    override fun scheduleFetch() {
        fetches++
    }

    override fun cancelAll() {
        cancelled++
    }
}
