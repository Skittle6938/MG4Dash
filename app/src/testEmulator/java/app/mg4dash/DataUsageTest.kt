package app.mg4dash

import android.content.Context
import app.mg4dash.system.DataUsage
import app.mg4dash.system.DataUsage.Day
import app.mg4dash.system.DataUsage.Snapshot
import app.mg4dash.system.DataUsage.Use
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.Locale

/** 4G data counted by use over 30 days: sums, settings, alert once per crossing, texts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DataUsageTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val mb = 1_000_000L

    @Before fun noAlertWaiting() { DataUsage.pendingAlert = null }

    // ── Sums ──

    @Test fun dayTotalIs4gOnly() {
        val d = Day(20261106, longArrayOf(10 * mb, 0, 2 * mb, 0, 0, 1 * mb), longArrayOf(5 * mb, 30 * mb, 0, 0, 0, 0))
        assertEquals(13 * mb, d.total)
        assertEquals(13 * mb, d.sum(g = true, w = false))
        assertEquals(35 * mb, d.sum(g = false, w = true))
        assertEquals(48 * mb, d.sum(g = true, w = true))
        assertEquals(0L, d.sum(g = false, w = false))
        assertArrayEquals(longArrayOf(15 * mb, 30 * mb, 2 * mb, 0, 0, 1 * mb), d.of(g = true, w = true))
    }

    @Test fun dayWithoutWifiCountsNone() {
        val d = Day(20261106, longArrayOf(1, 2, 3, 4, 5, 6))
        assertEquals(Use.values().size, d.wifi.size)
        assertEquals(0L, d.sum(g = false, w = true))
    }

    @Test fun snapshotSumsByUseAndNetwork() {
        val days = listOf(
            Day(20261105, longArrayOf(10 * mb, 0, 0, 0, 0, 0), longArrayOf(0, 0, 4 * mb, 0, 0, 0)),
            Day(20261106, longArrayOf(5 * mb, 40 * mb, 1 * mb, 0, 0, 0), longArrayOf(1 * mb, 0, 0, 0, 0, 0)),
        )
        val s = Snapshot(days, true)
        assertEquals(56 * mb, s.total)
        assertEquals(15 * mb, s.of(Use.MAP))
        assertEquals(40 * mb, s.of(Use.UPDATES))
        assertEquals(16 * mb, s.of(Use.MAP, g = true, w = true))
        assertEquals(5 * mb, s.of(Use.WEB, g = true, w = true))
        assertEquals(4 * mb, s.of(Use.WEB, g = false, w = true))
        assertEquals(61 * mb, s.total(g = true, w = true))
        assertEquals(5 * mb, s.total(g = false, w = true))
    }

    @Test fun usesHaveDistinctTagsAndWebCountedByUid() {
        val tags = Use.values().map { it.tag }
        assertEquals(tags.size, tags.toSet().size)
        assertEquals(0, Use.WEB.tag)
        assertTrue(Use.values().filter { it != Use.WEB }.all { it.tag != 0 })
    }

    // ── Quota bar and thresholds ──

    @Test fun quotaScaleGrowsPastTheLimit() {
        assertEquals(1_000_000_000L, DataUsage.scale(0))
        assertEquals(1_000_000_000L, DataUsage.scale(1_000_000_000L))
        assertEquals(2_000_000_000L, DataUsage.scale(1_000_000_001L))
        assertEquals(5_000_000_000L, DataUsage.scale(3_000_000_000L))
        assertEquals(10_000_000_000L, DataUsage.scale(9_999_999_999L))
        assertEquals(20_000_000_000L, DataUsage.scale(15_000_000_000L))
        assertEquals(50_000_000_000L, DataUsage.scale(60_000_000_000L))
    }

    @Test fun thresholdsAreSortedAndOfferTheDefault() {
        val t = DataUsage.THRESHOLDS.toList()
        assertEquals(t.sorted(), t)
        assertTrue(DataUsage.DEFAULT_THRESHOLD in t)
        assertTrue(DataUsage.MG_LIMIT in t)
    }

    // ── Dates ──

    @Test fun dateRoundTrip() {
        val c = Calendar.getInstance().apply { clear(); set(2026, Calendar.NOVEMBER, 6) }
        assertEquals(20261106, DataUsage.ymd(c))
        assertEquals(20261106, DataUsage.ymd(DataUsage.calendarOf(20261106)))
        // Year and month ends
        assertEquals(20261231, DataUsage.ymd(DataUsage.calendarOf(20261231)))
        assertEquals(20240229, DataUsage.ymd(DataUsage.calendarOf(20240229)))
        val next = DataUsage.calendarOf(20261231).apply { add(Calendar.DAY_OF_MONTH, 1) }
        assertEquals(20270101, DataUsage.ymd(next))
    }

    // ── Settings ──

    @Test fun settingsDefaults() {
        assertEquals(DataUsage.DEFAULT_THRESHOLD, DataUsage.threshold(ctx))
        assertTrue(DataUsage.popup(ctx))
        assertFalse(DataUsage.updatesWifiOnly(ctx))
        assertTrue(DataUsage.show4g(ctx))
        assertFalse(DataUsage.showWifi(ctx))
        assertFalse("updates never blocked by default", DataUsage.updatesBlocked(ctx))
    }

    @Test fun settingsAreKept() {
        DataUsage.setPopup(ctx, false)
        DataUsage.setUpdatesWifiOnly(ctx, true)
        DataUsage.setShown(ctx, g = false, w = true)
        DataUsage.setThreshold(ctx, 500_000_000L)
        assertFalse(DataUsage.popup(ctx))
        assertTrue(DataUsage.updatesWifiOnly(ctx))
        assertFalse(DataUsage.show4g(ctx))
        assertTrue(DataUsage.showWifi(ctx))
        assertEquals(500_000_000L, DataUsage.threshold(ctx))
    }

    // ── Reading (simulated month in the emulator build) ──

    @Test fun readingGivesThirtyDaysEndingToday() {
        DataUsage.refreshSoon(ctx)
        val s = DataUsage.last
        assertNotNull(s)
        s!!
        assertTrue(s.available)
        assertEquals(DataUsage.DAYS, s.days.size)
        assertEquals(DataUsage.ymd(Calendar.getInstance()), s.days.last().date)
        assertEquals(s.days.map { it.date }.sorted(), s.days.map { it.date })
        assertEquals(s.days.size, s.days.map { it.date }.toSet().size)
        assertEquals(s.total, Use.values().sumOf { s.of(it) })
    }

    @Test fun listenerGetsTheLastReadingAtOnce() {
        DataUsage.refreshSoon(ctx)
        var got: Snapshot? = null
        val l: (Snapshot) -> Unit = { got = it }
        DataUsage.listen(l)
        try { assertEquals(DataUsage.last, got) } finally { DataUsage.unlisten(l) }
    }

    // ── Alert ──

    /** The simulated month, and thresholds just under and just over its total. */
    private fun month(): Triple<Long, Long, Long> {
        DataUsage.refreshSoon(ctx)
        val total = DataUsage.last!!.total
        assertTrue("simulated month under MG's limit", total < DataUsage.MG_LIMIT)
        val under = DataUsage.THRESHOLDS.filter { it <= total }.maxOrNull() ?: error("no threshold under $total")
        val over = DataUsage.THRESHOLDS.first { it > total }
        return Triple(total, under, over)
    }

    @Test fun alertOncePerCrossing() {
        val (_, under, over) = month()
        DataUsage.setThreshold(ctx, over)
        assertNull("under the threshold: nothing", DataUsage.pendingAlert)
        DataUsage.setThreshold(ctx, under)
        assertEquals(1, DataUsage.pendingAlert)
        // Shown, then read again: not again while the total stays over the threshold
        DataUsage.pendingAlert = null
        DataUsage.refreshSoon(ctx)
        assertNull(DataUsage.pendingAlert)
        // Under it again, then over: a new message
        DataUsage.setThreshold(ctx, over)
        assertNull(DataUsage.pendingAlert)
        DataUsage.setThreshold(ctx, under)
        assertEquals(1, DataUsage.pendingAlert)
    }

    @Test fun noPopupWhenTurnedOff() {
        val (_, under, _) = month()
        DataUsage.setPopup(ctx, false)
        DataUsage.setThreshold(ctx, under)
        assertNull(DataUsage.pendingAlert)
    }

    @Test fun alertListenersAreCalled() {
        val (_, under, over) = month()
        DataUsage.setThreshold(ctx, over)
        var calls = 0
        val l: () -> Unit = { calls++ }
        DataUsage.onAlert(l)
        try {
            DataUsage.setThreshold(ctx, under)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(1, calls)
        } finally { DataUsage.offAlert(l) }
    }

    @Test fun resetAllowsTheAlertAgain() {
        val (_, under, _) = month()
        DataUsage.setThreshold(ctx, under)
        assertEquals(1, DataUsage.pendingAlert)
        DataUsage.pendingAlert = null
        // Back to zero: the alert level too, so the next crossing shows the message again
        DataUsage.reset(ctx)
        assertEquals(1, DataUsage.pendingAlert)
    }

    // ── Texts ──

    @Test fun formatEnglish() = withLocale(Locale.US) {
        assertEquals("0.4 MB", DataUsage.format(ctx, 412_000))
        assertEquals("9.9 MB", DataUsage.format(ctx, 9_940_000))
        assertEquals("412 MB", DataUsage.format(ctx, 412_000_000))
        assertEquals("1.27 GB", DataUsage.format(ctx, 1_270_000_000))
        assertEquals("12.5 GB", DataUsage.format(ctx, 12_500_000_000))
        assertEquals("750 MB", DataUsage.formatRound(ctx, 750_000_000))
        assertEquals("1 GB", DataUsage.formatRound(ctx, 1_000_000_000))
        assertEquals("1.5 GB", DataUsage.formatRound(ctx, 1_500_000_000))
    }

    @Test @Config(qualifiers = "fr") fun formatFrench() = withLocale(Locale.FRANCE) {
        assertEquals("1,27 Go", DataUsage.format(ctx, 1_270_000_000))
        assertEquals("412 Mo", DataUsage.format(ctx, 412_000_000))
        assertEquals("1,5 Go", DataUsage.formatRound(ctx, 1_500_000_000))
    }

    private fun withLocale(l: Locale, block: () -> Unit) {
        val before = Locale.getDefault()
        Locale.setDefault(l)
        try { block() } finally { Locale.setDefault(before) }
    }
}
