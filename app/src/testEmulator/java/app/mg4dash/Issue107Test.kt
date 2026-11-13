package app.mg4dash

import app.mg4dash.car.LegacyCar
import app.mg4dash.energy.EnergyMonitor
import app.mg4dash.energy.TripLog
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #107: a fan level the car gives for a moment in AUTO, and trip lengths across the car's clock being set. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class Issue107Test {
    @Test fun fanOutOfRangeKeepsTheLastValidLevel() {
        assertEquals("AUTO (15): the last manual level kept, − / + go on from it", 6, LegacyCar.validFan(LegacyCar.FAN_AUTO, 6))
        assertEquals(11, LegacyCar.validFan(11, 6))
        assertEquals("off", 0, LegacyCar.validFan(0, 6))
        assertEquals(null, LegacyCar.validFan(-1, null))
    }

    @Test fun tripLengthFromAClockThatDoesNotJump() {
        // 72 min later on the clock that does not jump
        EnergyMonitor.tripStartForTest(System.currentTimeMillis())
        val started = android.os.SystemClock.elapsedRealtime()
        val d = EnergyMonitor.tripDurationMs(started + 72 * 60_000L)
        org.junit.Assert.assertTrue("72 min, whatever the wall clock did", Math.abs(d - 72 * 60_000L) < 1_000L)
        EnergyMonitor.tripStartForTest(0L)
        assertEquals("no trip", 0L, EnergyMonitor.tripDurationMs())
    }

    @Test fun temperatureLimitsFromTheCar() {
        // SWI68 R67 / R73, recent SWI165: LO 15, HI 31, numbers 16 to 30
        assertEquals(15 to 31, LegacyCar.tempLimits(15, 31))
        assertEquals("R46", 17 to 33, LegacyCar.tempLimits(17, 33))
        assertEquals("not answered: the SWI165 stock app's values", 17 to 33, LegacyCar.tempLimits(null, null))
        assertEquals("implausible", 17 to 33, LegacyCar.tempLimits(-1, 0))
        val lo = app.mg4dash.car.VehicleState.tempLo; val hi = app.mg4dash.car.VehicleState.tempHi
        try {
            app.mg4dash.car.VehicleState.tempLo = 15f; app.mg4dash.car.VehicleState.tempHi = 31f
            assertEquals(16f, app.mg4dash.car.VehicleState.tempFirst)
            assertEquals(30f, app.mg4dash.car.VehicleState.tempLast)
            assertEquals("LO", app.mg4dash.car.VehicleState.tempLabel(15f))
            assertEquals("16°", app.mg4dash.car.VehicleState.tempLabel(16f))
            assertEquals("30°", app.mg4dash.car.VehicleState.tempLabel(30f))
            assertEquals("HI", app.mg4dash.car.VehicleState.tempLabel(31f))
        } finally { app.mg4dash.car.VehicleState.tempLo = lo; app.mg4dash.car.VehicleState.tempHi = hi }
        // SWI69: LO 17, HI 33, numbers 18 to 32, whole degrees only
        assertEquals("LO", app.mg4dash.car.VehicleState.tempLabel(17f))
        assertEquals("the car may report LO as 17.5", "LO", app.mg4dash.car.VehicleState.tempLabel(17.5f))
        assertEquals("no half degree shown", "22°", app.mg4dash.car.VehicleState.tempLabel(21.5f))
        assertEquals("18°", app.mg4dash.car.VehicleState.tempLabel(18f))
        assertEquals("HI", app.mg4dash.car.VehicleState.tempLabel(32.5f))
        assertEquals(18f, app.mg4dash.car.VehicleState.tempFirst)
        assertEquals(32f, app.mg4dash.car.VehicleState.tempLast)
    }

    /** SWI69 (A9): the range chosen as the stock climate app does (car model and nation). */
    @Test fun a9RangeFromTheCarModel() {
        val cb = app.mg4dash.car.CarBridge
        assertEquals("MG4 sold in France / EU", true, cb.tempRange16to30(69, 5))
        assertEquals("UK", true, cb.tempRange16to30(69, 8))
        assertEquals("Australia", true, cb.tempRange16to30(69, 24))
        assertEquals("EU/UK MY24", true, cb.tempRange16to30(132, null))
        assertEquals("another market", false, cb.tempRange16to30(69, 1))
        assertEquals("nation unknown: kept as it was", null, cb.tempRange16to30(69, null))
        assertEquals(null, cb.tempRange16to30(null, 5))
        val lo = app.mg4dash.car.VehicleState.tempLo; val hi = app.mg4dash.car.VehicleState.tempHi
        try {
            app.mg4dash.car.VehicleState.tempLo = 15.5f; app.mg4dash.car.VehicleState.tempHi = 30.5f
            assertEquals(16f, app.mg4dash.car.VehicleState.tempFirst)
            assertEquals(30f, app.mg4dash.car.VehicleState.tempLast)
            assertEquals("LO", app.mg4dash.car.VehicleState.tempLabel(15.5f))
            assertEquals("16°", app.mg4dash.car.VehicleState.tempLabel(16f))
            assertEquals("HI", app.mg4dash.car.VehicleState.tempLabel(30.5f))
        } finally { app.mg4dash.car.VehicleState.tempLo = lo; app.mg4dash.car.VehicleState.tempHi = hi }
    }

    @Test fun absurdSavedTripLengthsNotShown() {
        val day = 86_400_000L
        assertEquals(72L, TripLog.Trip(1_000_000L, 1_000_000L + 72 * 60_000L, 0, 10, 10f, 2f).minutes)
        // Started on 1 Jan 2019 (the clock not set yet), ended in 2026: not shown as years
        assertEquals(0L, TripLog.Trip(1_546_300_800_000L, 1_546_300_800_000L + 2_800 * day, 0, 10, 10f, 2f).minutes)
    }
}
