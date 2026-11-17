package app.mg4dash

import app.mg4dash.energy.ConsumptionCurve
import app.mg4dash.ui.atMost
import app.mg4dash.ui.curveSlopes
import app.mg4dash.ui.smoothed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ChartPointsTest {
    @Test fun shortListUnchanged() {
        val v = List(50) { it.toFloat() }
        assertSame(v, v.atMost(50))
    }

    @Test fun longListAveragedInOrder() {
        val r = List(5000) { it.toFloat() }.atMost(50)
        assertEquals(50, r.size)
        assertEquals(49.5f, r.first(), 0.01f)
        assertEquals(4949.5f, r.last(), 0.01f)
        assertEquals(r.sorted(), r)
    }

    @Test fun constantStaysConstantWhenSlicesAreUneven() {
        assertEquals(List(50) { 15f }, List(173) { 15f }.atMost(50))
    }

    @Test fun timeLinePlacesPointsByDateAndKeepsGaps() {
        val day = 86_400_000L
        // Two groups of trips separated by 40 days without driving
        val times = List(5) { it * day } + List(5) { (45 + it) * day }
        val line = ConsumptionCurve.timeLine(times, List(10) { 15f }, 50)
        assertEquals(10, line.size)
        assertEquals(0f, line.first().first, 0.001f)
        assertEquals(1f, line.last().first, 0.03f)
        assertEquals(true, line.none { it.first > 0.1f && it.first < 0.9f })
        assertEquals(true, line.all { it.second == 15f })
    }

    @Test fun timeLineAtMostFiftyPoints() {
        val line = ConsumptionCurve.timeLine(List(5000) { it * 60_000L }, List(5000) { 10f + it % 7 })
        assertEquals(50, line.size)
    }

    @Test fun timeLineSmoothsOnlyAcrossNeighbours() {
        val line = ConsumptionCurve.timeLine(listOf(0L, 10L, 20L, 1000L), listOf(10f, 20f, 30f, 90f), 50)
        // The first slice (10 and 20) stays itself: a centred window has no slice before it
        assertEquals(15f, line[0].second, 0.01f)
        assertEquals(90f, line.last().second, 0.01f)
    }

    /** #44: the curve's end is the real latest value, the window narrowing 5, 3, 1 towards the edges. */
    @Test fun smoothingNarrowsAtTheEnds() {
        val s = listOf(10f, 10f, 10f, 10f, 10f, 10f, 30f).smoothed()
        assertEquals(30f, s.last(), 0.001f)                  // alone: the present, not the average of the last 3
        assertEquals((10f + 10f + 30f) / 3, s[5], 0.001f)    // 3 points
        assertEquals((10f * 4 + 30f) / 5, s[4], 0.001f)      // 5 points
        assertEquals(10f, s.first(), 0.001f)
        // Nothing invented: a constant stays constant, and the length is kept
        assertEquals(List(9) { 12f }, List(9) { 12f }.smoothed())
        assertEquals(listOf(4f, 8f), listOf(4f, 8f).smoothed())
    }

    /** #44: trips under 2 km stay out of the 12 months, though their own average shows from 0.5 km (#29). */
    @Test fun shortTripsLeftOutOfTheYear() {
        val short = app.mg4dash.energy.EnergyMonitor.Totals(outKwh = 0.3f, km = 1, dist = 1.6f)
        assertEquals(true, short.per100 != null)
        assertEquals(null, ConsumptionCurve.yearAverage(short))
        // 2.4 km counted 2 by the odometer: in, at its precise average
        val ok = app.mg4dash.energy.EnergyMonitor.Totals(outKwh = 0.4f, km = 2, dist = 2.4f)
        assertEquals(0.4f / 2.4f * 100f, ConsumptionCurve.yearAverage(ok)!!, 0.01f)
    }

    /** #44 cleanup: no-breakdown trips since #29 go, older history and known distances decide otherwise. */
    @Test fun shortTripsCleanup() {
        val since = ConsumptionCurve.SHORT_TRIPS_SINCE
        val mix = ConsumptionCurve.Mix(10f, 2f, 0f, 1f)
        val old = since - 1000L to 15f                // before #29, no breakdown: kept
        val withMix = since + 1000L to 14f            // breakdown: a trip from 2 km, kept
        val short = since + 2000L to 30f              // no breakdown since #29: a short trip, dropped
        val known = since + 3000L to 18f              // no breakdown but 2.6 km known: kept
        val knownShort = since + 4000L to 25f         // breakdown but 1.2 km known: dropped
        val kept = ConsumptionCurve.withoutShortTrips(listOf(old, withMix, short, known, knownShort),
            mapOf(withMix.first to mix, knownShort.first to mix), mapOf(known.first to 2.6f, knownShort.first to 1.2f))
        assertEquals(listOf(old, withMix, known), kept)
    }

    /** #44: the items start from zero; a point without breakdown only adds its total, last. */
    @Test fun stackItemsFromZeroUnknownLast() {
        val mix = ConsumptionCurve.Mix(10f, 3f, 1f, 2f)
        val parts = app.mg4dash.ui.ConsumptionCard.stackParts(listOf(16f, 22f), listOf(mix, null)) { it }
        assertEquals(5, parts.size)
        assertEquals(listOf(3f, 0f), parts[0])        // climate, bottom
        assertEquals(listOf(10f, 0f), parts[3])       // driving
        assertEquals(listOf(0f, 22f), parts.last())   // the unknown total on top
        assertEquals(listOf(16f, 22f), parts[0].indices.map { i -> parts.sumOf { it[i].toDouble() }.toFloat() })
    }

    @Test fun timeLineEndsOnTheLastSlice() {
        val times = List(20) { it * 1000L }
        val v = List(19) { 10f } + 40f
        val line = ConsumptionCurve.timeLine(times, v, 20)
        assertEquals(40f, line.last().second, 0.01f)
        assertEquals((10f + 10f + 40f) / 3, line[line.size - 2].second, 0.01f)
    }

    @Test fun slopesFlatAtPeaksSoTheCurveNeverOvershoots() {
        val s = curveSlopes(floatArrayOf(0f, 1f, 2f, 3f), floatArrayOf(0f, 10f, 2f, 5f))
        assertEquals(0f, s[1], 0.001f)
        assertEquals(0f, s[2], 0.001f)
    }
}
