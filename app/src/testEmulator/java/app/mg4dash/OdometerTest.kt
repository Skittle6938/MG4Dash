package app.mg4dash

import app.mg4dash.car.CarBridge
import app.mg4dash.energy.EnergyMonitor
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Odometer without a general service (SWI165, #102): reread on each pass, and the average kWh/100 follows it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OdometerTest {

    @Test fun propertyReadAgainMoves() {
        var odo: Int? = null
        listOf(50183, 50184, 50190, 50242).forEach { odo = CarBridge.odometer(it, odo) }
        assertEquals(50242, odo)
    }

    @Test fun missingOrLateReadKeepsTheKm() {
        assertEquals(50242, CarBridge.odometer(null, 50242))
        assertEquals(50242, CarBridge.odometer(0, 50242))
        assertEquals("a late source does not take km back", 50242, CarBridge.odometer(50183, 50242))
        assertEquals(50183, CarBridge.odometer(50183, null))
    }

    @Test fun averageConsumptionFollowsTheOdometer() {
        // 20 km at 60 km/h and 15 kWh/100, the car's odometer property read each second
        val d = EnergyMonitor.Distance()
        val p = EnergyMonitor.Distance.Period()
        var odo = CarBridge.odometer(50183, null)!!
        d.step(odo, 0f, 1000L, p)
        var truth = 0f
        repeat(1200) {
            truth += 60f / 3600f
            odo = CarBridge.odometer(50183 + kotlin.math.floor(0.4f + truth).toInt(), odo)!!
            d.step(odo, 60f, 1000L, p)
        }
        val dist = p.km(odo, d.fraction, odo - 50183)
        assertEquals(truth, dist, 0.1f)
        val t = EnergyMonitor.Totals(outKwh = truth * 0.15f, km = odo - 50183, dist = dist)
        assertEquals(15f, t.per100!!, 0.2f)
    }
}
