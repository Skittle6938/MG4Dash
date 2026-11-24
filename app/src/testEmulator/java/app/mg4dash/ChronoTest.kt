package app.mg4dash

import app.mg4dash.chrono.ChronoHistory
import app.mg4dash.chrono.ChronoMath
import app.mg4dash.chrono.ChronoMath.Sample
import app.mg4dash.chrono.ChronoRunData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The chrono (24/11): exact crossing instants from stepped speed readings, and the records. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChronoTest {

    /** Readings of a car starting at [t0] with a rising acceleration, a new whole km/h value every [step] s. */
    private fun run(t0: Double, step: Double, until: Double, v: (Double) -> Double): List<Sample> {
        val out = ArrayList<Sample>()
        var t = t0 - 0.5
        while (t <= until) {
            val s = if (t < t0) 0.0 else Math.floor(v(t - t0))
            if (out.lastOrNull()?.v != s) out += Sample(t, s)
            t += step
        }
        return out
    }

    @Test
    fun crossingUsesTheAccelerationBeforeTheThreshold() {
        // Readings every 0.1 s, the speed rising faster and faster: v = 4 t + 0.6 t² (km/h)
        val v = { t: Double -> 4 * t + 0.6 * t * t }
        val s = run(10.0, 0.1, 30.0, v)
        val exact = 10.0 + (-4 + Math.sqrt(16 + 4 * 0.6 * 50)) / (2 * 0.6)
        val got = ChronoMath.crossing(s, 50.0)!!
        // Within one reading's step of the true instant, as the speed is only read in whole km/h every 0.1 s
        assertEquals(exact, got, 0.1)
        assertNull(ChronoMath.crossing(s, 900.0))
    }

    @Test
    fun crossingIsExactOnAConstantAcceleration() {
        // Exact readings of v = 13 t at irregular times: the curve finds the true instant
        val ts = listOf(0.0, 0.13, 0.31, 0.42, 0.6, 0.77, 0.95)
        val s = ts.map { Sample(it, 13.0 * it) }
        assertEquals(10.0 / 13.0, ChronoMath.crossing(s, 10.0)!!, 1e-6)
    }

    @Test
    fun startIsTakenBackToZero() {
        // 0 last read at 4.95 s, then 3 km/h at 5.10 s and 6 km/h at 5.25 s (20 km/h a second): left 0 at 4.95 s
        val s = listOf(Sample(4.95, 0.0), Sample(5.10, 3.0), Sample(5.25, 6.0))
        assertEquals(4.95, ChronoMath.start(s, 4.95)!!, 1e-9)
        // Without the last zero: taken back along the acceleration
        assertEquals(4.95, ChronoMath.start(s, null)!!, 1e-9)
        // Never before the last zero read
        val late = listOf(Sample(5.05, 0.0), Sample(5.10, 3.0), Sample(5.25, 6.0))
        assertEquals(5.05, ChronoMath.start(late, 5.05)!!, 1e-9)
    }

    @Test
    fun targetsInMph() {
        val t = ChronoMath.targets(ChronoMath.Kind.STANDING, mph = true)
        assertEquals(96.56, t.to, 0.01)
        assertEquals(48.28, t.split!!, 0.01)
        val r = ChronoMath.targets(ChronoMath.Kind.ROLLING, mph = false)
        assertEquals(80.0, r.from, 0.0); assertNull(r.split)
    }

    private fun r(at: Long, full: Float, split: Float? = full * 0.4f, climb: Float? = 0f, kind: ChronoMath.Kind = ChronoMath.Kind.STANDING) =
        ChronoRunData(at, kind, false, split, full, 80, 12f, climb, 150f, 58f, 120f, true, true, false, floatArrayOf(0f, 10f, Float.NaN), floatArrayOf(0f, 5f))

    @Test
    fun theRecordStaysWhenItsRunDropsOut() {
        val h = ChronoHistory()
        assertEquals(setOf(ChronoHistory.Rec.SPLIT, ChronoHistory.Rec.FULL), h.add(r(1, 7.5f)))
        for (i in 2L..12L) h.add(r(i, 8f + i * 0.01f))
        assertEquals(ChronoHistory.KEEP, h.runs.size)
        assertFalse(h.runs.any { it.at == 1L })
        assertEquals(1L, h.record(ChronoHistory.Rec.FULL, false)!!.at)
        // Erasing the history keeps the records; erasing everything does not
        h.clear(records = false)
        assertEquals(1L, h.record(ChronoHistory.Rec.FULL, false)!!.at)
        h.clear(records = true)
        assertNull(h.record(ChronoHistory.Rec.FULL, false))
    }

    @Test
    fun deletingTheRecordsRunTakesTheBestLeft() {
        val h = ChronoHistory()
        h.add(r(1, 8f)); h.add(r(2, 7.4f)); h.add(r(3, 7.9f))
        h.remove(2)
        assertEquals(3L, h.record(ChronoHistory.Rec.FULL, false)!!.at)
        h.remove(3); h.remove(1)
        assertNull(h.record(ChronoHistory.Rec.FULL, false))
    }

    @Test
    fun aRunDownhillIsKeptButNeverARecord() {
        val h = ChronoHistory()
        h.add(r(1, 7.8f))
        // 3 m down: faster, kept, but the record stays
        assertTrue(h.add(r(2, 7.1f, climb = -3f)).isEmpty())
        assertEquals(2L, h.runs.first().at)
        assertEquals(1L, h.record(ChronoHistory.Rec.FULL, false)!!.at)
        assertFalse(ChronoHistory.counts(h.runs.first()))
        // Exactly the threshold does not count either; just above it does, and an unknown slope counts
        assertFalse(ChronoHistory.counts(r(9, 7f, climb = ChronoHistory.DOWNHILL_M)))
        assertTrue(ChronoHistory.counts(r(9, 7f, climb = -1.9f)))
        assertTrue(ChronoHistory.counts(r(9, 7f, climb = null)))
        // Deleting the record never hands it to the downhill run
        h.remove(1)
        assertNull(h.record(ChronoHistory.Rec.FULL, false))
    }

    @Test
    fun keptAcrossRestarts() {
        val h = ChronoHistory()
        h.add(r(1, 7.5f)); h.add(r(2, 8.1f, kind = ChronoMath.Kind.ROLLING, split = null))
        val back = ChronoHistory.fromJson(h.toJson())
        assertEquals(2, back.runs.size)
        assertEquals(7.5f, back.record(ChronoHistory.Rec.FULL, false)!!.full, 0.001f)
        assertEquals(2L, back.record(ChronoHistory.Rec.ROLLING, false)!!.at)
        assertTrue(back.runs.last().curve[2].isNaN())
        assertEquals(0, ChronoHistory.fromJson("not json").runs.size)
    }
}
