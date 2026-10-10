package com.mtajchert.carom.app.core.domain

import com.mtajchert.carom.app.core.data.sync.SendResult
import com.mtajchert.carom.app.core.protocol.MessageBodies
import com.mtajchert.carom.app.core.protocol.MessageTypes
import com.mtajchert.carom.app.core.testing.sync.FakeCaromSync
import com.mtajchert.carom.app.core.testing.sync.FakeSyncWorkScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SendClipboardUseCaseTest {
    private val sync = FakeCaromSync()
    private val scheduler = FakeSyncWorkScheduler()
    private val sendClipboard = SendClipboardUseCase(sync, scheduler)

    @Test
    fun `sends a clipboard set body`() = runTest {
        assertEquals(ClipboardSendOutcome.SENT, sendClipboard("hi", sensitive = true, onlyTo = setOf("dev_a")))
        val sent = sync.sent.single()
        assertEquals(MessageTypes.CLIPBOARD_SET, sent.type)
        assertTrue(MessageTypes.isValidBody(sent.type, sent.body))
        assertEquals(true, MessageBodies.fromJson(MessageBodies.ClipboardSet.serializer(), sent.body)!!.sensitive)
        assertEquals(setOf("dev_a"), sent.onlyTo)
        assertEquals(0, scheduler.flushes)
    }

    @Test
    fun `refuses empty and oversized text before touching the engine`() = runTest {
        assertEquals(ClipboardSendOutcome.EMPTY, sendClipboard("", sensitive = false))
        assertEquals(ClipboardSendOutcome.TOO_LARGE, sendClipboard("x".repeat(SendClipboardUseCase.MAX_TEXT_BYTES + 1), sensitive = false))
        assertTrue(sync.sent.isEmpty())
    }

    @Test
    fun `a queued send schedules background work`() = runTest {
        sync.nextSendResult = SendResult.Queued("01a1268a-e800-7000-8000-000000000000")
        assertEquals(ClipboardSendOutcome.QUEUED, sendClipboard("later", sensitive = false))
        assertEquals(1, scheduler.flushes)
    }
}
