package app.mg4dash

import app.mg4dash.energy.EnergyMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** kWh/100 km of a period (energy measured by the car's power) and of the sliding last km. The estimate from the battery % is in EnergyEstimatesTest. */
class ConsumptionTest {

    // ── Totals of a period ──

    @Test fun netEnergyDeductsRegeneration() {
        val t = EnergyMonitor.Totals(outKwh = 5f, regenKwh = 1f, km = 25)
        assertEquals(4f, t.netKwh, 0.0001f)
        assertEquals(16f, t.per100!!, 0.01f)
    }

    @Test fun moreRegenerationThanOutputIsZeroNotNegative() {
        val t = EnergyMonitor.Totals(outKwh = 1f, regenKwh = 2f, km = 10)
        assertEquals(0f, t.netKwh, 0f)
        assertNull(t.per100)
    }

    @Test fun drivingIsWhatTheClimateHeatingAndAccessoriesLeave() {
        val t = EnergyMonitor.Totals(outKwh = 10f, climKwh = 2f, accKwh = 0.5f, heatKwh = 1.5f, km = 50)
        assertEquals(6f, t.driveKwh, 0.0001f)
        // Never negative, even with estimated parts over the measure
        assertEquals(0f, EnergyMonitor.Totals(outKwh = 1f, climKwh = 2f).driveKwh, 0f)
    }

    @Test fun preciseDistanceWinsOverWholeKm() {
        assertEquals(12.4f, EnergyMonitor.Totals(km = 12, dist = 12.4f).distKm, 0f)
        assertEquals(12f, EnergyMonitor.Totals(km = 12).distKm, 0f)
        assertEquals(15f, EnergyMonitor.Totals(outKwh = 1.86f, km = 12, dist = 12.4f).per100!!, 0.01f)
    }

    @Test fun implausibleValuesAreNotShown() {
        // Over 60 kWh/100 (a few hundred metres with the heating on)
        assertNull(EnergyMonitor.Totals(outKwh = 2f, km = 2).per100)
        // Under 3 kWh/100
        assertNull(EnergyMonitor.Totals(outKwh = 0.15f, km = 10).per100)
        assertEquals(55f, EnergyMonitor.Totals(outKwh = 1.1f, km = 2).per100!!, 0.01f)
    }

    @Test fun tooLittleEnergyIsNotShown() {
        assertNull(EnergyMonitor.Totals(outKwh = 0.1f, km = 2).per100)
        assertNull(EnergyMonitor.Totals(outKwh = 0.02f, dist = 0.6f).per100)
    }

    @Test fun emptyPeriod() {
        val t = EnergyMonitor.Totals()
        assertNull(t.per100)
        assertEquals(0f, t.distKm, 0f)
    }

    @Test fun slidingKmRestartsWhenTheTripDoes() {
        val w = EnergyMonitor.SlidingKm()
        for (i in 0..15) w.add(i * 0.1f, i * 0.015f)
        // A new trip: the distance goes back, and the window starts again
        assertNull(w.add(0.1f, 0.01f))
        assertEquals(10f, w.add(0.7f, 0.07f)!!, 0.5f)
    }

    @Test fun slidingKmIgnoresImplausibleValues() {
        val w = EnergyMonitor.SlidingKm()
        w.add(0f, 0f)
        // 1 kWh over 600 m: 166 kWh/100
        assertNull(w.add(0.6f, 1f))
    }
}
