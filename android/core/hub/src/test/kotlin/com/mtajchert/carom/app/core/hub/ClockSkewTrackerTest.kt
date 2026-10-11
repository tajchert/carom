package com.mtajchert.carom.app.core.hub

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone

class ClockSkewTrackerTest {
    private val now = Instant.parse("2026-10-11T22:34:07Z").toEpochMilli()
    private val tracker = ClockSkewTracker { now }
    private val savedZone = TimeZone.getDefault()
    private val savedLocale = Locale.getDefault()

    @After
    fun restore() {
        TimeZone.setDefault(savedZone)
        Locale.setDefault(savedLocale)
    }

    private fun date(offsetMs: Long): String =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(now + offsetMs).atZone(java.time.ZoneOffset.UTC))

    @Test
    fun `a correct clock shows nothing`() {
        tracker.onHubDate("Sun, 11 Oct 2026 22:34:07 GMT")
        assertNull(tracker.drift.value)
    }

    @Test
    fun `local clock behind the hub`() {
        tracker.onHubDate(date(10 * 60_000L))
        val drift = tracker.drift.value!!
        assertEquals(10L, drift.minutes)
        assertFalse(drift.localAhead)
    }

    @Test
    fun `local clock ahead of the hub`() {
        tracker.onHubDate(date(-7 * 60_000L))
        val drift = tracker.drift.value!!
        assertEquals(7L, drift.minutes)
        assertTrue(drift.localAhead)
    }

    @Test
    fun `exactly five minutes does not warn`() {
        tracker.onHubDate(date(300_000L))
        assertNull(tracker.drift.value)
    }

    @Test
    fun `hysteresis keeps the warning until under four minutes`() {
        tracker.onHubDate(date(6 * 60_000L))
        assertEquals(6L, tracker.drift.value?.minutes)
        tracker.onHubDate(date(4 * 60_000L + 30_000L)) // between 4 and 5 minutes: still on
        assertEquals(5L, tracker.drift.value?.minutes)
        tracker.onHubDate(date(3 * 60_000L + 59_000L))
        assertNull(tracker.drift.value)
        tracker.onHubDate(date(4 * 60_000L + 30_000L)) // between thresholds while off: stays off
        assertNull(tracker.drift.value)
    }

    @Test
    fun `unparseable or missing Date is ignored`() {
        tracker.onHubDate(date(10 * 60_000L))
        tracker.onHubDate(null)
        tracker.onHubDate("not a date")
        tracker.onHubDate("")
        assertEquals(10L, tracker.drift.value?.minutes)
    }

    @Test
    fun `hub_time feeds the same check`() {
        tracker.onHubTime(now - 20 * 60_000L)
        assertTrue(tracker.drift.value!!.localAhead)
        tracker.onHubTime(now)
        assertNull(tracker.drift.value)
    }

    @Test
    fun `default time zone and locale do not change the result`() {
        val header = date(-9 * 60_000L)
        val expected = run { tracker.onHubDate(header); tracker.drift.value }
        for (zone in listOf("Pacific/Kiritimati", "America/Los_Angeles", "Asia/Kolkata")) {
            for (locale in listOf(Locale.GERMANY, Locale("ar", "EG"), Locale.JAPAN)) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                Locale.setDefault(locale)
                val fresh = ClockSkewTracker { now }
                fresh.onHubDate(header)
                assertEquals(expected, fresh.drift.value)
            }
        }
        assertEquals(now, ClockSkewTracker.parseHttpDate("Sun, 11 Oct 2026 22:34:07 GMT"))
    }
}
