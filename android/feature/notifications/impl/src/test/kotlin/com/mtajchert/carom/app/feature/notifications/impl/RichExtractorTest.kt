package com.mtajchert.carom.app.feature.notifications.impl

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.IconCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mtajchert.carom.app.core.protocol.MessageBodies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Reads real `Notification`/`StatusBarNotification` objects built through the platform builders. */
@RunWith(AndroidJUnit4::class)
class RichExtractorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val placeholder = RedactionDetector.ENGLISH_PLACEHOLDER
    private val extractor = RichExtractor(AppLabelResolver(context), RedactionDetector(setOf(placeholder)))

    private fun extract(n: Notification, pkg: String = "com.chat", postTime: Long = 1, importance: Int? = null) =
        extractor.extractWithImportance(sbn(n, pkg, postTime), importance)

    private val contentIntent: PendingIntent
        get() = PendingIntent.getActivity(context, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)

    @Test
    fun `standard template reads title, text, sub text, category and post time`() {
        val n = builder()
            .setContentTitle("Anna")
            .setContentText("hello")
            .setSubText("Work")
            .setCategory(Notification.CATEGORY_EMAIL)
            .build()
        val s = extract(n, pkg = "com.mail", postTime = 42)
        assertEquals("Anna", s.title)
        assertEquals("hello", s.text)
        assertEquals("Work", s.subText)
        assertEquals("email", s.category)
        assertEquals(42L, s.postedAt)
        assertEquals("com.mail", s.packageName)
        assertTrue(s.key.contains("com.mail"))
        assertFalse(s.redacted)
        assertNull(s.importance)
        assertEquals(RichContent.TEMPLATE_STANDARD, s.rich.template)
        assertFalse(s.rich.canOpen)
        assertTrue(ImageSlot.AppIcon in s.imageSources)
        assertTrue(ImageSlot.SmallIcon in s.imageSources)
        assertNull(NotificationFilter.structural(s, context.packageName))
    }

    @Test
    fun `big text keeps the collapsed text and sends the expanded text separately`() {
        val n = builder()
            .setContentTitle("Anna")
            .setContentText("short")
            .setStyle(NotificationCompat.BigTextStyle().bigText("the long version").setSummaryText("Inbox"))
            .build()
        val s = extract(n)
        assertEquals("short", s.text)
        assertEquals(RichContent.TEMPLATE_BIG_TEXT, s.rich.template)
        assertEquals("the long version", s.rich.bigText)
        assertEquals("Inbox", s.rich.summaryText)

        val same = builder().setContentText("short").setStyle(NotificationCompat.BigTextStyle().bigText("short")).build()
        assertNull(extract(same).rich.bigText)
    }

    @Test
    fun `big text without a collapsed text falls back to the big text`() {
        val n = builder().setContentTitle("T").setStyle(NotificationCompat.BigTextStyle().bigText("only big")).build()
        assertEquals("only big", extract(n).text)
    }

    @Test
    fun `inbox template keeps the first seven lines`() {
        val style = NotificationCompat.InboxStyle().setSummaryText("+3 more")
        for (i in 1..10) style.addLine("line $i")
        val s = extract(builder().setContentTitle("Mail").setStyle(style).build())
        assertEquals(RichContent.TEMPLATE_INBOX, s.rich.template)
        assertEquals((1..7).map { "line $it" }, s.rich.lines)
        assertEquals("+3 more", s.rich.summaryText)
    }

    @Test
    fun `big picture template references the picture, its description and the large icon`() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val n = builder()
            .setLargeIcon(bitmap)
            .setStyle(NotificationCompat.BigPictureStyle().bigPicture(bitmap).setContentDescription("A cat"))
            .build()
        val s = extract(n)
        assertEquals(RichContent.TEMPLATE_BIG_PICTURE, s.rich.template)
        assertEquals("A cat", s.rich.pictureDesc)
        assertTrue(ImageSlot.Picture in s.imageSources)
        assertTrue(ImageSlot.LargeIcon in s.imageSources)
        assertFalse(ImageSlot.Picture in extract(builder().build()).imageSources)
    }

    @Test
    fun `custom views and the decorated style are the custom template`() {
        val n = builder().setStyle(NotificationCompat.DecoratedCustomViewStyle()).build()
        assertEquals(RichContent.TEMPLATE_CUSTOM, extract(n).rich.template)
    }

    @Test
    fun `colour, content intent, group, time, chronometer and channel are read`() {
        val n = builder()
            .setColor(Color.RED)
            .setContentIntent(contentIntent)
            .setGroup("chat")
            .setSortKey("b")
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setWhen(123_456)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .build()
        val s = extractor.extractWithImportance(sbn(n), importance = 4)
        assertEquals(Color.RED, s.rich.color)
        assertTrue(s.rich.canOpen)
        assertEquals(MessageBodies.Group(key = "chat", summary = false, sortKey = "b", alert = "summary"), s.rich.group)
        assertEquals(123_456L, s.rich.whenMs)
        assertEquals(true, s.rich.showWhen)
        assertEquals(MessageBodies.Chronometer(countDown = true), s.rich.chronometer)
        assertEquals(MessageBodies.Channel(id = "channel", importance = 4), s.rich.channel)
        assertEquals(4, s.importance)

        val plain = extract(builder().build())
        assertNull(plain.rich.color)
        assertNull(plain.rich.group)
        assertNull(plain.rich.chronometer)
        assertNull(plain.rich.channel) // no ranking, no importance
    }

    @Test
    fun `colorized is reported only for foreground services`() {
        assertNull(extract(builder().setColorized(true).build()).rich.colorized)
        val fgs = builder().setColorized(true).build().apply { flags = flags or Notification.FLAG_FOREGROUND_SERVICE }
        assertEquals(true, extract(fgs).rich.colorized)
    }

    @Test
    fun `actions keep their original index, semantic action and reply choices`() {
        val reply = NotificationCompat.Action.Builder(0, "Reply", contentIntent)
            .addRemoteInput(RemoteInput.Builder("r").setLabel("Say something").setChoices(arrayOf("Yes", "No")).build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .build()
        val b = builder().addAction(reply)
        for (i in 1..4) b.addAction(0, "Action $i", contentIntent)
        val actions = extract(b.build()).rich.actions
        assertEquals(5, actions.size)
        assertEquals(
            MessageBodies.Action(
                index = 0,
                title = "Reply",
                semantic = Notification.Action.SEMANTIC_ACTION_REPLY,
                reply = MessageBodies.ReplySpec(label = "Say something", choices = listOf("Yes", "No")),
            ),
            actions[0],
        )
        assertEquals(MessageBodies.Action(index = 3, title = "Action 3"), actions[3])
    }

    @Test
    fun `50 actions are capped at 6`() {
        val b = builder()
        for (i in 0 until 50) b.addAction(0, "Action $i", contentIntent)
        val actions = extract(b.build()).rich.actions
        assertEquals(6, actions.size)
        assertEquals((0..5).toList(), actions.map { it.index })
    }

    @Test
    fun `system autogroup summaries are recognised by their override group key`() {
        val summary = builder().setGroup("g").setGroupSummary(true).build()
        val app = extract(summary)
        assertTrue(app.groupSummary)
        assertFalse(app.autogroupSummary)
        assertTrue(app.rich.group!!.summary)
        assertNull(NotificationFilter.structural(app, context.packageName))

        val system = sbn(summary).apply { overrideGroupKey = "ranker_group" }
        val auto = extractor.extractWithImportance(system, null)
        assertTrue(auto.autogroupSummary)
        assertEquals(SkipReason.AUTOGROUP_SUMMARY, NotificationFilter.structural(auto, context.packageName))
    }

    @Test
    fun `plain text is used without a style`() {
        val s = extract(builder().setContentTitle("T").setContentText("plain").build())
        assertEquals("plain", s.text)
    }

    @Test
    fun `flags map to the structural and ongoing filters`() {
        fun flagged(configure: NotificationCompat.Builder.() -> Unit) = extract(builder().apply(configure).build())

        assertEquals(SkipReason.ONGOING, NotificationFilter.ongoing(flagged { setOngoing(true) }, forwardOngoing = false))
        assertNull(NotificationFilter.ongoing(flagged { setOngoing(true) }, forwardOngoing = true))
        assertEquals(SkipReason.LOCAL_ONLY, NotificationFilter.structural(flagged { setLocalOnly(true) }, context.packageName))

        val fgs = builder().build().apply { flags = flags or Notification.FLAG_FOREGROUND_SERVICE }
        val snapshot = extract(fgs)
        assertTrue(snapshot.foregroundService)
        assertEquals(SkipReason.ONGOING, NotificationFilter.ongoing(snapshot, forwardOngoing = false))
    }

    @Test
    fun `media notifications are detected by template and category`() {
        val media = Notification.Builder(context, "c").setStyle(Notification.MediaStyle()).build()
        assertTrue(extract(media).mediaStyle)
        val transport = builder().setCategory(Notification.CATEGORY_TRANSPORT).build()
        assertTrue(extract(transport).mediaStyle)
        assertFalse(extract(builder().build()).mediaStyle)
    }

    @Test
    fun `android 15 redaction is flagged and the placeholder is not forwarded`() {
        val n = builder().setContentTitle("Bank").setContentText(placeholder).build()
        val s = extract(n)
        assertTrue(s.redacted)
        assertNull(s.text)
        assertEquals("Bank", s.title)

        val inbox = NotificationCompat.InboxStyle().addLine(placeholder).addLine("fine")
        val i = extract(builder().setStyle(inbox).build())
        assertTrue(i.redacted)
        assertEquals(listOf("fine"), i.rich.lines)
    }

    @Test
    fun `redaction is only recognised on Android 15 and later`() {
        val old = RedactionDetector(setOf(placeholder), sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        assertFalse(old.isRedacted(placeholder))
        val new = RedactionDetector(setOf(placeholder), sdkInt = Build.VERSION_CODES.VANILLA_ICE_CREAM)
        assertTrue(new.isRedacted(" $placeholder "))
        assertFalse(new.isRedacted("Your code is 123456"))
        assertFalse(new.isRedacted(null))
        assertTrue(placeholder in RedactionDetector.systemPlaceholders())
    }

    @Test
    fun `labels come from the package manager, then the notification's app info, then the package name`() {
        val resolver = AppLabelResolver(context)
        assertEquals("com.unknown", resolver.label("com.unknown", null))
        // Builders put the posting app's ApplicationInfo into the extras; an invisible package resolves through it.
        val n = builder().build()
        val viaExtras = AppLabelResolver(context).label("com.invisible", n.extras)
        assertEquals(context.applicationInfo.loadLabel(context.packageManager).toString(), viaExtras)
    }

    private fun person(name: String, key: String? = null, withIcon: Boolean = false): Person =
        Person.Builder().setName(name).apply {
            key?.let { setKey(it) }
            if (withIcon) setIcon(IconCompat.createWithBitmap(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)))
        }.build()

    @Test
    fun `messaging keeps the V1 title and text and fills the conversation`() {
        val me = person("Me")
        val anna = person("Anna", key = "anna")
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle("Weekend")
            .setGroupConversation(true)
            .addMessage("first", 1, anna)
            .addMessage("last one", 2, anna)
        val s = extract(builder().setStyle(style).build())
        assertEquals(RichContent.TEMPLATE_MESSAGING, s.rich.template)
        assertEquals("Anna: last one", s.text)
        assertEquals("Weekend", s.title)
        assertEquals(SnapConversation(title = "Weekend", isGroup = true), s.rich.conversation)
        assertEquals(listOf("first", "last one"), s.rich.messages.map { it.text })
        assertEquals(listOf(1L, 2L), s.rich.messages.map { it.ts })
        assertEquals("anna", s.rich.messages.last().senderKey)
        assertEquals("Anna", s.rich.messages.last().senderName)

        val mine = NotificationCompat.MessagingStyle(me).addMessage("from me", 3, null as Person?)
        val m = extract(builder().setStyle(mine).build())
        assertEquals("Me: from me", m.text)
        assertEquals(false, m.rich.conversation!!.isGroup)
    }

    @Test
    fun `200 messages are capped at the newest 6, oldest first, with avatars and images`() {
        val anna = person("Anna", withIcon = true)
        val style = NotificationCompat.MessagingStyle(person("Me"))
        repeat(200) { i -> style.addMessage("m$i", i.toLong(), anna) }
        style.addMessage(
            NotificationCompat.MessagingStyle.Message("look", 500, anna)
                .setData("image/png", Uri.parse("content://media/external/images/1")),
        )
        val s = extract(builder().setStyle(style).build())
        assertEquals(listOf("m195", "m196", "m197", "m198", "m199", "look"), s.rich.messages.map { it.text })
        assertEquals("Anna: look", s.text)
        assertTrue((0..5).all { ImageSlot.SenderIcon(it) in s.imageSources })
        assertTrue(s.imageSources[ImageSlot.MessageImage(5)] is ImageSource.FromUri)
        assertFalse(ImageSlot.MessageImage(0) in s.imageSources)
    }

    @Test
    fun `a conversation's large icon is its avatar`() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val style = NotificationCompat.MessagingStyle(person("Me")).addMessage("hi", 1, person("Anna"))
        val s = extract(builder().setLargeIcon(bitmap).setStyle(style).build())
        assertTrue(ImageSlot.ConversationIcon in s.imageSources)
        assertFalse(ImageSlot.LargeIcon in s.imageSources)
    }

    @Test
    fun `a placeholder message is flagged redacted and sent empty`() {
        val anna = person("Anna")
        val style = NotificationCompat.MessagingStyle(anna).addMessage(placeholder, 1, anna)
        val m = extract(builder().setStyle(style).build())
        assertTrue(m.redacted)
        assertNull(m.text)
        assertEquals("", m.rich.messages.single().text)
    }

    @Test
    fun `a malformed messaging bundle omits the conversation but keeps the rest`() {
        val n = builder().setContentTitle("Chat").setContentIntent(contentIntent).build()
        n.extras.putString(Notification.EXTRA_TEMPLATE, "android.app.Notification\$MessagingStyle")
        n.extras.putString(Notification.EXTRA_MESSAGES, "not an array of bundles")
        val s = extract(n)
        assertEquals("Chat", s.title)
        assertTrue(s.rich.canOpen)
        assertTrue(s.rich.messages.isEmpty())
        assertNull(s.rich.conversation)
    }

    private val promotedFlag = 0x00040000

    private fun segmentBundle(length: Int, id: Int, color: Int = 0) =
        Bundle().apply {
            putInt("length", length)
            putInt("id", id)
            putInt("colorInt", color)
        }

    private fun pointBundle(position: Int, id: Int, color: Int = 0) =
        Bundle().apply {
            putInt("position", position)
            putInt("id", id)
            putInt("colorInt", color)
        }

    @Test
    fun `legacy progress is the progress template with its value and maximum`() {
        val s = extract(builder().setContentTitle("Download").setProgress(100, 40, false).setOngoing(true).build())
        assertEquals(RichContent.TEMPLATE_PROGRESS, s.rich.template)
        assertEquals(SnapProgress(value = 40, max = 100, indeterminate = false), s.rich.progress)
        assertNull(NotificationFilter.ongoing(s, forwardOngoing = false))

        val spinner = extract(builder().setProgress(0, 0, true).build())
        assertEquals(SnapProgress(value = 0, max = 0, indeterminate = true), spinner.rich.progress)

        assertNull(extract(builder().setProgress(0, 0, false).build()).rich.progress)
        assertNull(extract(builder().build()).rich.progress)
    }

    @Test
    fun `progress keeps a style's template when it has one`() {
        val s = extract(
            builder().setStyle(NotificationCompat.BigTextStyle().bigText("x")).setProgress(10, 5, false).build(),
        )
        assertEquals(RichContent.TEMPLATE_BIG_TEXT, s.rich.template)
        assertEquals(5, s.rich.progress!!.value)
    }

    @Test
    fun `ProgressStyle extras are read by string key with segments, points and icons`() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA)
        val extras = Bundle().apply {
            putString(Notification.EXTRA_TEMPLATE, "android.app.Notification\$ProgressStyle")
            putInt("android.progress", 40)
            putInt("android.progressMax", 100)
            putParcelableArrayList(
                "android.progressSegments",
                ArrayList((1..12).map { segmentBundle(length = it * 10, id = it, color = if (it == 1) Color.RED else 0) }),
            )
            putParcelableArrayList(
                "android.progressPoints",
                ArrayList((1..6).map { pointBundle(position = it * 10, id = it, color = Color.GREEN) }),
            )
            putParcelable("android.progressTrackerIcon", Icon.createWithBitmap(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)))
            putParcelable("android.progressStartIcon", Icon.createWithBitmap(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)))
        }
        val s = extract(builder().addExtras(extras).build())
        assertEquals(RichContent.TEMPLATE_PROGRESS, s.rich.template)
        val p = s.rich.progress!!
        assertEquals(40, p.value)
        assertEquals(100, p.max)
        assertEquals(10, p.segments.size)
        assertEquals(MessageBodies.Segment(length = 10, color = Color.RED), p.segments[0])
        assertEquals(MessageBodies.Segment(length = 20, color = null), p.segments[1])
        assertEquals(4, p.points.size)
        assertEquals(MessageBodies.Point(position = 10, color = Color.GREEN), p.points[0])
        assertTrue(ImageSlot.TrackerIcon in s.imageSources)
        assertTrue(ImageSlot.StartIcon in s.imageSources)
        assertFalse(ImageSlot.EndIcon in s.imageSources)
    }

    @Test
    fun `progress points with a negative position are dropped before the cap`() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA)
        val positions = listOf(-5, 10, -1, 20, 30, 40, 50)
        val extras = Bundle().apply {
            putInt("android.progress", 40)
            putInt("android.progressMax", 100)
            putParcelableArrayList(
                "android.progressPoints",
                ArrayList(positions.mapIndexed { i, pos -> pointBundle(position = pos, id = i + 1) }),
            )
        }
        val p = extract(builder().addExtras(extras).build()).rich.progress!!
        assertEquals(listOf(10, 20, 30, 40), p.points.map { it.position })
    }

    @Test
    fun `a real ProgressStyle notification is read through the same keys`() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA)
        val style = Notification.ProgressStyle()
            .setProgress(40)
            .setProgressSegments(
                listOf(
                    Notification.ProgressStyle.Segment(30).setColor(Color.RED),
                    Notification.ProgressStyle.Segment(70),
                ),
            )
            .setProgressPoints(listOf(Notification.ProgressStyle.Point(25).setColor(Color.GREEN)))
        val n = Notification.Builder(context, "channel").setSmallIcon(android.R.drawable.ic_dialog_info).setStyle(style).build()
        val s = extract(n)
        assertEquals(RichContent.TEMPLATE_PROGRESS, s.rich.template)
        val p = s.rich.progress!!
        assertEquals(40, p.value)
        assertEquals(100, p.max)
        assertEquals(listOf(30, 70), p.segments.map { it.length })
        assertEquals(listOf(25), p.points.map { it.position })
    }

    @Test
    fun `malformed segments are dropped but the progress is still sent`() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA)
        val extras = Bundle().apply {
            putInt("android.progress", 3)
            putInt("android.progressMax", 10)
            putStringArrayList("android.progressSegments", arrayListOf("not", "bundles"))
            putString("android.progressPoints", "also wrong")
        }
        val s = extract(builder().setContentTitle("Sync").addExtras(extras).build())
        assertEquals("Sync", s.title)
        val p = s.rich.progress!!
        assertEquals(3, p.value)
        assertEquals(10, p.max)
        assertTrue(p.segments.isEmpty())
        assertTrue(p.points.isEmpty())
    }

    @Test
    fun `a promoted Live Update carries its short critical text and passes the ongoing filter`() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA)
        val extras = Bundle().apply { putString("android.shortCriticalText", "12 min") }
        val n = builder().setOngoing(true).addExtras(extras).build().apply { flags = flags or promotedFlag }
        val s = extract(n)
        assertTrue(s.promoted)
        assertEquals(MessageBodies.Live(promoted = true, shortCriticalText = "12 min"), s.rich.live)
        assertNull(NotificationFilter.ongoing(s, forwardOngoing = false))

        val plain = extract(builder().setOngoing(true).build())
        assertFalse(plain.promoted)
        assertNull(plain.rich.live)
        assertEquals(SkipReason.ONGOING, NotificationFilter.ongoing(plain, forwardOngoing = false))

        val onlyText = extract(builder().addExtras(extras).build())
        assertEquals(MessageBodies.Live(promoted = false, shortCriticalText = "12 min"), onlyText.rich.live)
    }

    @Test
    fun `call style reads the type, caller, video flag and verification text`() {
        val caller = android.app.Person.Builder()
            .setName("Anna")
            .setIcon(Icon.createWithBitmap(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)))
            .build()
        val extras = Bundle().apply {
            putString(Notification.EXTRA_TEMPLATE, "android.app.Notification\$CallStyle")
            putInt(Notification.EXTRA_CALL_TYPE, Notification.CallStyle.CALL_TYPE_INCOMING)
            putParcelable(Notification.EXTRA_CALL_PERSON, caller)
            putBoolean(Notification.EXTRA_CALL_IS_VIDEO, true)
            putCharSequence(Notification.EXTRA_VERIFICATION_TEXT, "Verified caller")
        }
        val s = extract(builder().setOngoing(true).addExtras(extras).build())
        assertEquals(RichContent.TEMPLATE_CALL, s.rich.template)
        assertEquals(
            SnapCall(type = "incoming", personName = "Anna", isVideo = true, verification = "Verified caller"),
            s.rich.call,
        )
        assertTrue(ImageSlot.CallPersonIcon in s.imageSources)
        assertNull(NotificationFilter.ongoing(s, forwardOngoing = false))

        fun typeOf(code: Int) = extract(
            builder().addExtras(
                Bundle().apply {
                    putString(Notification.EXTRA_TEMPLATE, "android.app.Notification\$CallStyle")
                    putInt(Notification.EXTRA_CALL_TYPE, code)
                },
            ).build(),
        ).rich.call!!.type
        assertEquals("ongoing", typeOf(Notification.CallStyle.CALL_TYPE_ONGOING))
        assertEquals("screening", typeOf(Notification.CallStyle.CALL_TYPE_SCREENING))
        assertEquals("unknown", typeOf(Notification.CallStyle.CALL_TYPE_UNKNOWN))
    }

    private fun builder() = NotificationCompat.Builder(context, "channel").setSmallIcon(android.R.drawable.ic_dialog_info)

    @Suppress("DEPRECATION")
    private fun sbn(n: Notification, pkg: String = "com.chat", postTime: Long = 1) =
        StatusBarNotification(pkg, pkg, 1, null, 10_000, 0, 0, n, Process.myUserHandle(), postTime)
}
