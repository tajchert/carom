package com.mtajchert.carom.app.feature.clipboard.impl

import com.mtajchert.carom.app.core.data.sync.SendResult
import com.mtajchert.carom.app.core.domain.SendClipboardUseCase
import com.mtajchert.carom.app.core.testing.sync.FakeCaromSync
import com.mtajchert.carom.app.core.testing.sync.FakeSyncWorkScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardSenderTest {
    private val sync = FakeCaromSync()
    private val scheduler = FakeSyncWorkScheduler()
    private val echo = EchoGuard()
    private val targets = FakeSendTargets()
    private val sender = ClipboardSender(SendClipboardUseCase(sync, scheduler), echo, targets)

    @Test
    fun `sends to everyone by default`() = runTest {
        assertEquals(ClipboardSendStatus.SENT, sender.send("hi", sensitive = false))

        val sent = sync.sent.single()
        assertEquals(null, sent.onlyTo)
    }

    @Test
    fun `passes the sensitive flag and the selected devices on`() = runTest {
        targets.state.value = setOf("dev_a")

        sender.send("secret", sensitive = true)

        assertEquals(setOf("dev_a"), sync.sent.single().onlyTo)
        assertEquals("true", sync.sent.single().body["sensitive"].toString())
    }

    @Test
    fun `skips text that just arrived from another device`() = runTest {
        echo.remember("round trip")

        assertEquals(ClipboardSendStatus.SKIPPED_ECHO, sender.send("round trip", sensitive = false))
        assertTrue(sync.sent.isEmpty())
        assertEquals(ClipboardSendStatus.SENT, sender.send("new text", sensitive = false))
    }

    @Test
    fun `an empty selection sends nowhere`() = runTest {
        targets.state.value = emptySet()

        assertEquals(ClipboardSendStatus.NO_RECIPIENTS, sender.send("hi", sensitive = false))
        assertTrue(sync.sent.isEmpty())
    }

    @Test
    fun `engine results map to statuses and a queued send schedules a flush`() = runTest {
        sync.nextSendResult = SendResult.TooLarge
        assertEquals(ClipboardSendStatus.TOO_LARGE, sender.send("x", false))
        sync.nextSendResult = SendResult.Queued("id")
        assertEquals(ClipboardSendStatus.QUEUED, sender.send("x", false))
        assertEquals(1, scheduler.flushes)
        sync.nextSendResult = SendResult.NotPaired
        assertEquals(ClipboardSendStatus.NOT_PAIRED, sender.send("x", false))
    }

    @Test
    fun `text over 64 KB is refused before the engine is called`() = runTest {
        assertEquals(ClipboardSendStatus.TOO_LARGE, sender.send("a".repeat(70_000), false))
        assertTrue(sync.sent.isEmpty())
    }
}
