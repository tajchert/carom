package com.mtajchert.carom.app.feature.notifications.impl.actions

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mtajchert.carom.app.core.protocol.MessageBodies.ActionRef
import com.mtajchert.carom.app.feature.notifications.impl.ActiveNotificationsBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real `Notification`/`StatusBarNotification` objects, real `PendingIntent`s and a recording
 * [PendingIntentSender]. Review Focus 2: a stale or vanished target must never fire anything.
 */
@RunWith(AndroidJUnit4::class)
class ActionExecutorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val bridge = ActiveNotificationsBridge()
    private val sender = RecordingSender()
    private val executor = ActionExecutor(bridge, sender)
    private val live = mutableMapOf<String, StatusBarNotification>()

    @Before
    fun attach() {
        bridge.attach(
            object : ActiveNotificationsBridge.Listener {
                override fun isActive(key: String) = key in live
                override fun cancel(key: String) {
                    live -= key
                }

                override fun find(key: String) = live[key]
            },
        )
    }

    private fun pending(name: String): PendingIntent =
        PendingIntent.getBroadcast(context, 0, Intent("test.$name").setPackage(context.packageName), PendingIntent.FLAG_MUTABLE)

    private fun action(title: String, intent: PendingIntent?, reply: Boolean = false): Notification.Action {
        val icon = Icon.createWithResource(context, android.R.drawable.ic_dialog_info)
        val builder = Notification.Action.Builder(icon, title, intent)
        if (reply) builder.addRemoteInput(RemoteInput.Builder("reply_key").setLabel("Reply").build())
        return builder.build()
    }

    private fun post(key: String = KEY, configure: Notification.Builder.() -> Unit = {}) {
        val n = Notification.Builder(context, "channel").setSmallIcon(android.R.drawable.ic_dialog_info).apply(configure).build()
        @Suppress("DEPRECATION")
        live[key] = StatusBarNotification("com.chat", "com.chat", 1, null, 10_000, 0, 0, n, Process.myUserHandle(), 1)
    }

    @Test
    fun `tap fires the content intent`() {
        val open = pending("open")
        post { setContentIntent(open) }

        assertEquals(ActionOutcome.OK, executor.execute(KEY, ActionRef.Tap))
        val sent = sender.sent.single()
        assertSame(open, sent.intent)
        assertNull(sent.fillIn)
        assertNotNull(sent.options) // background activity start is allowed on API 34+; the test SDK is 36
    }

    @Test
    fun `tap without a content intent is a changed notification`() {
        post()
        assertEquals(ActionOutcome.CHANGED, executor.execute(KEY, ActionRef.Tap))
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun `a button is matched by index and title`() {
        val archive = pending("archive")
        val mute = pending("mute")
        post {
            addAction(action("Archive", archive))
            addAction(action("Mute", mute))
        }

        assertEquals(ActionOutcome.OK, executor.execute(KEY, ActionRef.Button(1, "Mute", null)))
        assertSame(mute, sender.sent.single().intent)
        assertNull(sender.sent.single().fillIn)
    }

    @Test
    fun `reordered buttons answer changed and fire nothing`() {
        val archive = pending("archive")
        val mute = pending("mute")
        // The Mac saw [Archive, Mute]; the app has since swapped them.
        post {
            addAction(action("Mute", mute))
            addAction(action("Archive", archive))
        }

        assertEquals(ActionOutcome.CHANGED, executor.execute(KEY, ActionRef.Button(0, "Archive", null)))
        assertEquals(ActionOutcome.CHANGED, executor.execute(KEY, ActionRef.Button(1, "Mute", null)))
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun `a title that differs even slightly is a changed notification`() {
        post { addAction(action("Reply", pending("reply"))) }
        assertEquals(ActionOutcome.CHANGED, executor.execute(KEY, ActionRef.Button(0, "Reply ", null)))
        assertEquals(ActionOutcome.CHANGED, executor.execute(KEY, ActionRef.Button(0, "reply", null)))
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun `an index past the last action is a changed notification`() {
        post { addAction(action("Reply", pending("reply"))) }
        assertEquals(ActionOutcome.CHANGED, executor.execute(KEY, ActionRef.Button(1, "Reply", null)))
        assertEquals(ActionOutcome.CHANGED, executor.execute(KEY, ActionRef.Button(-1, "Reply", null)))
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun `a notification that lost all its actions is changed`() {
        post()
        assertEquals(ActionOutcome.CHANGED, executor.execute(KEY, ActionRef.Button(0, "Reply", null)))
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun `a button without an intent is changed`() {
        post { addAction(action("Archive", null)) }
        assertEquals(ActionOutcome.CHANGED, executor.execute(KEY, ActionRef.Button(0, "Archive", null)))
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun `a cleared notification is gone for every kind of action`() {
        post { setContentIntent(pending("open")) }
        live.clear()

        assertEquals(ActionOutcome.GONE, executor.execute(KEY, ActionRef.Tap))
        assertEquals(ActionOutcome.GONE, executor.execute(KEY, ActionRef.Answer))
        assertEquals(ActionOutcome.GONE, executor.execute(KEY, ActionRef.Decline))
        assertEquals(ActionOutcome.GONE, executor.execute(KEY, ActionRef.Button(0, "Reply", "hi")))
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun `nothing can be found while the listener is not connected`() {
        post { setContentIntent(pending("open")) }
        val detached = ActiveNotificationsBridge()
        assertEquals(ActionOutcome.GONE, ActionExecutor(detached, sender).execute(KEY, ActionRef.Tap))
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun `a reply fills the free form remote input and marks its source`() {
        val send = pending("send")
        post { addAction(action("Reply", send, reply = true)) }

        assertEquals(ActionOutcome.OK, executor.execute(KEY, ActionRef.Button(0, "Reply", "on my way")))
        val sent = sender.sent.single()
        assertSame(send, sent.intent)
        val fillIn = sent.fillIn!!
        assertEquals("on my way", RemoteInput.getResultsFromIntent(fillIn)!!.getCharSequence("reply_key").toString())
        assertEquals(RemoteInput.SOURCE_FREE_FORM_INPUT, RemoteInput.getResultsSource(fillIn))
        assertNotNull(sent.options)
    }

    @Test
    fun `reply text for a button that has no remote input is a changed notification`() {
        post { addAction(action("Archive", pending("archive"))) }
        assertEquals(ActionOutcome.CHANGED, executor.execute(KEY, ActionRef.Button(0, "Archive", "hello")))
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun `answer and decline use the call style intents`() {
        val answer = pending("answer")
        val decline = pending("decline")
        post {
            addExtras(
                Bundle().apply {
                    putParcelable(Notification.EXTRA_ANSWER_INTENT, answer)
                    putParcelable(Notification.EXTRA_DECLINE_INTENT, decline)
                },
            )
        }

        assertEquals(ActionOutcome.OK, executor.execute(KEY, ActionRef.Answer))
        assertEquals(ActionOutcome.OK, executor.execute(KEY, ActionRef.Decline))
        assertSame(answer, sender.sent[0].intent)
        assertSame(decline, sender.sent[1].intent)
    }

    @Test
    fun `answer on a notification that is not a call is changed`() {
        post { addAction(action("Archive", pending("archive"))) }
        assertEquals(ActionOutcome.CHANGED, executor.execute(KEY, ActionRef.Answer))
        assertEquals(ActionOutcome.CHANGED, executor.execute(KEY, ActionRef.Decline))
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun `before API 31 the incoming call buttons are the first and the last action`() {
        val n = Notification.Builder(context, "channel")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .addExtras(Bundle().apply { putInt(NotificationCompat.EXTRA_CALL_TYPE, NotificationCompat.CallStyle.CALL_TYPE_INCOMING) })
            .addAction(action("Decline", pending("decline")))
            .addAction(action("Speaker", pending("speaker")))
            .addAction(action("Answer", pending("answer")))
            .build()

        assertEquals("Answer", ActionExecutor.legacyCallAction(n, answer = true)?.title.toString())
        assertEquals("Decline", ActionExecutor.legacyCallAction(n, answer = false)?.title.toString())

        val ongoing = Notification.Builder(context, "channel").setSmallIcon(android.R.drawable.ic_dialog_info).build()
        assertNull(ActionExecutor.legacyCallAction(ongoing, answer = true))
    }

    @Test
    fun `a canceled pending intent is failed and a refused start is blocked`() {
        post { setContentIntent(pending("open")) }

        sender.failure = PendingIntent.CanceledException()
        assertEquals(ActionOutcome.FAILED, executor.execute(KEY, ActionRef.Tap))

        sender.failure = SecurityException("background activity start refused")
        assertEquals(ActionOutcome.BLOCKED, executor.execute(KEY, ActionRef.Tap))

        sender.failure = IllegalStateException("surprise")
        assertEquals(ActionOutcome.FAILED, executor.execute(KEY, ActionRef.Tap))
    }

    @Test
    fun `outcomes carry the wire error codes`() {
        assertEquals(
            listOf(null, "gone", "changed", "blocked", "failed"),
            ActionOutcome.entries.map { it.error },
        )
    }

    private companion object {
        const val KEY = "0|com.chat|1|null|10000"
    }
}
