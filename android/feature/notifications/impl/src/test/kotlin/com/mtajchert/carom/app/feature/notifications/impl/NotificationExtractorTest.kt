package com.mtajchert.carom.app.feature.notifications.impl

import android.app.Notification
import android.content.Context
import android.os.Build
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Reads real `Notification`/`StatusBarNotification` objects built through the platform builders. */
@RunWith(AndroidJUnit4::class)
class NotificationExtractorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val placeholder = RedactionDetector.ENGLISH_PLACEHOLDER
    private val extractor = NotificationExtractor(AppLabelResolver(context), RedactionDetector(setOf(placeholder)))

    @Test
    fun `reads title, big text, sub text, category and post time`() {
        val n = builder()
            .setContentTitle("Anna")
            .setContentText("short")
            .setStyle(NotificationCompat.BigTextStyle().bigText("the long version"))
            .setSubText("Work")
            .setCategory(Notification.CATEGORY_EMAIL)
            .build()
        val s = extractor.extract(sbn(n, pkg = "com.mail", postTime = 42), ranking = null)
        assertEquals("Anna", s.title)
        assertEquals("the long version", s.text)
        assertEquals("Work", s.subText)
        assertEquals("email", s.category)
        assertEquals(42L, s.postedAt)
        assertEquals("com.mail", s.packageName)
        assertTrue(s.key.contains("com.mail"))
        assertFalse(s.redacted)
        assertNull(s.importance)
        assertNull(NotificationFilter.structural(s, context.packageName))
    }

    @Test
    fun `plain text is used without a big text style`() {
        val s = extractor.extract(sbn(builder().setContentTitle("T").setContentText("plain").build()), null)
        assertEquals("plain", s.text)
    }

    @Test
    fun `messaging style sends the last message as sender colon text`() {
        val me = Person.Builder().setName("Me").build()
        val anna = Person.Builder().setName("Anna").build()
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle("Weekend")
            .addMessage("first", 1, anna)
            .addMessage("last one", 2, anna)
        val s = extractor.extract(sbn(builder().setStyle(style).build()), null)
        assertEquals("Anna: last one", s.text)
        assertEquals("Weekend", s.title)

        val mine = NotificationCompat.MessagingStyle(me).addMessage("from me", 3, null as Person?)
        assertEquals("Me: from me", extractor.extract(sbn(builder().setStyle(mine).build()), null).text)
    }

    @Test
    fun `flags map to the structural filters`() {
        fun flagged(configure: NotificationCompat.Builder.() -> Unit) = extractor.extract(sbn(builder().apply(configure).build()), null)

        assertEquals(SkipReason.ONGOING, NotificationFilter.structural(flagged { setOngoing(true) }, context.packageName))
        assertEquals(SkipReason.GROUP_SUMMARY, NotificationFilter.structural(flagged { setGroup("g").setGroupSummary(true) }, context.packageName))
        assertEquals(SkipReason.LOCAL_ONLY, NotificationFilter.structural(flagged { setLocalOnly(true) }, context.packageName))

        val fgs = builder().build().apply { flags = flags or Notification.FLAG_FOREGROUND_SERVICE }
        assertTrue(extractor.extract(sbn(fgs), null).foregroundService)
    }

    @Test
    fun `media notifications are detected by template and category`() {
        val media = Notification.Builder(context, "c").setStyle(Notification.MediaStyle()).build()
        assertTrue(extractor.extract(sbn(media), null).mediaStyle)
        val transport = builder().setCategory(Notification.CATEGORY_TRANSPORT).build()
        assertTrue(extractor.extract(sbn(transport), null).mediaStyle)
        assertFalse(extractor.extract(sbn(builder().build()), null).mediaStyle)
    }

    @Test
    fun `android 15 redaction is flagged and the placeholder is not forwarded`() {
        val n = builder().setContentTitle("Bank").setContentText(placeholder).build()
        val s = extractor.extract(sbn(n), null)
        assertTrue(s.redacted)
        assertNull(s.text)
        assertEquals("Bank", s.title)

        val anna = Person.Builder().setName("Anna").build()
        val style = NotificationCompat.MessagingStyle(anna).addMessage(placeholder, 1, anna)
        val m = extractor.extract(sbn(builder().setStyle(style).build()), null)
        assertTrue(m.redacted)
        assertNull(m.text)
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

    private fun builder() = NotificationCompat.Builder(context, "channel").setSmallIcon(android.R.drawable.ic_dialog_info)

    @Suppress("DEPRECATION")
    private fun sbn(n: Notification, pkg: String = "com.chat", postTime: Long = 1) =
        StatusBarNotification(pkg, pkg, 1, null, 10_000, 0, 0, n, Process.myUserHandle(), postTime)
}
