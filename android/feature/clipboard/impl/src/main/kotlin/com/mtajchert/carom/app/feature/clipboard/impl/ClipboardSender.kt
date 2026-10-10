package com.mtajchert.carom.app.feature.clipboard.impl

import com.mtajchert.carom.app.core.domain.ClipboardSendOutcome
import com.mtajchert.carom.app.core.domain.SendClipboardUseCase
import javax.inject.Inject

enum class ClipboardSendStatus { SENT, QUEUED, TOO_LARGE, EMPTY, NO_RECIPIENTS, FAILED, NOT_PAIRED, SKIPPED_ECHO }

/**
 * The single entry for every send path (transparent activity, share target, tile): applies echo
 * suppression and the "Send clipboard to" selection, then hands over to [SendClipboardUseCase].
 */
class ClipboardSender @Inject constructor(
    private val sendClipboard: SendClipboardUseCase,
    private val echo: EchoGuard,
    private val targets: ClipboardSendTargets,
) {
    suspend fun send(text: String, sensitive: Boolean): ClipboardSendStatus {
        if (text.isEmpty()) return ClipboardSendStatus.EMPTY
        if (echo.isEcho(text)) return ClipboardSendStatus.SKIPPED_ECHO
        val onlyTo = targets.current()
        if (onlyTo != null && onlyTo.isEmpty()) return ClipboardSendStatus.NO_RECIPIENTS
        return when (sendClipboard(text = text, sensitive = sensitive, onlyTo = onlyTo)) {
            ClipboardSendOutcome.SENT -> ClipboardSendStatus.SENT
            ClipboardSendOutcome.QUEUED -> ClipboardSendStatus.QUEUED
            ClipboardSendOutcome.TOO_LARGE -> ClipboardSendStatus.TOO_LARGE
            ClipboardSendOutcome.EMPTY -> ClipboardSendStatus.EMPTY
            ClipboardSendOutcome.NO_RECIPIENTS -> ClipboardSendStatus.NO_RECIPIENTS
            ClipboardSendOutcome.FAILED -> ClipboardSendStatus.FAILED
            ClipboardSendOutcome.NOT_PAIRED -> ClipboardSendStatus.NOT_PAIRED
        }
    }
}
