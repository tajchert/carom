package com.mtajchert.carom.app.core.domain

import com.mtajchert.carom.app.core.data.sync.CaromSync
import com.mtajchert.carom.app.core.data.sync.SendResult
import com.mtajchert.carom.app.core.data.work.SyncWorkScheduler
import com.mtajchert.carom.app.core.protocol.MessageBodies
import com.mtajchert.carom.app.core.protocol.MessageTypes
import javax.inject.Inject

/** Outcome of sending the clipboard, for the share sheet, tile and in-app button. */
enum class ClipboardSendOutcome { SENT, QUEUED, TOO_LARGE, EMPTY, NO_RECIPIENTS, FAILED, NOT_PAIRED }

/**
 * Sends text as `clipboard.set@1` (docs/CLIENTS.md "Clipboard"). Text over 64 KB is refused.
 * A queued send is handed to WorkManager so it goes out once the network is back.
 */
class SendClipboardUseCase @Inject constructor(
    private val sync: CaromSync,
    private val scheduler: SyncWorkScheduler,
) {
    suspend operator fun invoke(
        text: String,
        sensitive: Boolean,
        source: String? = null,
        onlyTo: Set<String>? = null,
    ): ClipboardSendOutcome {
        if (text.isEmpty()) return ClipboardSendOutcome.EMPTY
        if (text.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) return ClipboardSendOutcome.TOO_LARGE
        val body = MessageBodies.toJson(
            MessageBodies.ClipboardSet.serializer(),
            MessageBodies.ClipboardSet(text = text, sensitive = sensitive, source = source),
        )
        return when (val result = sync.send(MessageTypes.CLIPBOARD_SET, body, onlyTo = onlyTo)) {
            is SendResult.Sent -> ClipboardSendOutcome.SENT
            is SendResult.Queued -> {
                scheduler.scheduleOutboxFlush()
                ClipboardSendOutcome.QUEUED
            }
            SendResult.NoRecipients -> ClipboardSendOutcome.NO_RECIPIENTS
            SendResult.TooLarge -> ClipboardSendOutcome.TOO_LARGE
            is SendResult.Dropped -> ClipboardSendOutcome.FAILED
            SendResult.NotPaired -> ClipboardSendOutcome.NOT_PAIRED
        }
    }

    companion object {
        /**
         * 64 KB of UTF-8 text. The payload JSON around it must still fit the 65 536-byte plaintext
         * limit, so text close to this size can also come back as [ClipboardSendOutcome.TOO_LARGE].
         */
        const val MAX_TEXT_BYTES = 64 * 1024
    }
}
