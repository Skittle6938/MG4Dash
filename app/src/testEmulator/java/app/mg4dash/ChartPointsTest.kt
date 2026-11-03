package app.mg4dash

import app.mg4dash.energy.ConsumptionCurve
import app.mg4dash.ui.atMost
import app.mg4dash.ui.curveSlopes
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
        // Deux groupes de trajets séparés par 40 jours sans rouler
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
        assertEquals(22.5f, line[0].second, 0.01f)
        assertEquals(90f, line.last().second, 0.01f)
    }

    @Test fun slopesFlatAtPeaksSoTheCurveNeverOvershoots() {
        val s = curveSlopes(floatArrayOf(0f, 1f, 2f, 3f), floatArrayOf(0f, 10f, 2f, 5f))
        assertEquals(0f, s[1], 0.001f)
        assertEquals(0f, s[2], 0.001f)
    }
}
