package app.mg4dash

import app.mg4dash.energy.EnergyMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Distance to the tenth of a km and sliding last km of the energy card (#29). */
class PreciseDistanceTest {

    /** [seconds] at [kmh], the odometer moving a km each time the true distance passes one, from [odo0] + [offset]. */
    private fun drive(d: EnergyMonitor.Distance, p: EnergyMonitor.Distance.Period, odo0: Int, offset: Float, kmh: Float, seconds: Int): Pair<Float, Int> {
        var truth = 0f
        var odo = odo0
        repeat(seconds) {
            truth += kmh / 3600f
            odo = odo0 + kotlin.math.floor(offset + truth).toInt()
            d.step(odo, kmh, 1000L, p)
        }
        return truth to odo
    }

    @Test fun startOfTripToTheTenth() {
        val d = EnergyMonitor.Distance()
        val p = EnergyMonitor.Distance.Period()
        d.step(1000, 0f, 1000L, p)
        // 2.9 km at 60 km/h, the odometer at 1000.7 when leaving: the whole km count would say 3 (or 2)
        val (truth, odo) = drive(d, p, 1000, 0.7f, 60f, 174)
        val km = p.km(odo, d.fraction, odo - 1000)
        assertEquals(truth, km, 0.05f)
    }

    @Test fun longTripStaysOnTheOdometer() {
        val d = EnergyMonitor.Distance()
        val p = EnergyMonitor.Distance.Period()
        d.step(5000, 0f, 1000L, p)
        // The speed reads 3 % high: over 100 km the speed alone would be 3 km off, not the anchored distance
        var truth = 0f
        var odo = 5000
        repeat(6000) {
            truth += 60f / 3600f
            odo = 5000 + kotlin.math.floor(0.3f + truth).toInt()
            d.step(odo, 60f * 1.03f, 1000L, p)
        }
        assertEquals(truth, p.km(odo, d.fraction, odo - 5000), 0.1f)
    }

    @Test fun noSpeedWholeKm() {
        val d = EnergyMonitor.Distance()
        d.step(1000, null, 1000L)
        assertEquals(false, d.speedKnown)
        val t = EnergyMonitor.Totals(outKwh = 0.5f, km = 1)
        assertNull("whole km only: from 2 km", t.per100)
        assertNotNull(EnergyMonitor.Totals(outKwh = 0.4f, km = 2).per100)
    }

    @Test fun preciseFromHalfAKm() {
        assertNull(EnergyMonitor.Totals(outKwh = 0.06f, km = 0, dist = 0.4f).per100)
        assertEquals(15f, EnergyMonitor.Totals(outKwh = 0.09f, km = 0, dist = 0.6f).per100!!, 0.01f)
    }

    @Test fun slidingLastKm() {
        val w = EnergyMonitor.SlidingKm()
        assertNull(w.add(0f, 0f))
        // 1 km at 20 kWh/100, then 1 km at 10 kWh/100: the window ends on the second only
        var kwh = 0f
        var km = 0f
        repeat(100) { km += 0.01f; kwh += 0.002f; w.add(km, kwh) }
        var last: Float? = null
        repeat(100) { km += 0.01f; kwh += 0.001f; last = w.add(km, kwh) }
        assertEquals(10f, last!!, 0.6f)
    }

    @Test fun slidingFromHalfAKm() {
        val w = EnergyMonitor.SlidingKm()
        var km = 0f
        var r: Float? = null
        repeat(40) { km += 0.01f; r = w.add(km, km * 0.15f) }
        assertNull(r)
        repeat(20) { km += 0.01f; r = w.add(km, km * 0.15f) }
        assertEquals(15f, r!!, 0.1f)
    }
}
