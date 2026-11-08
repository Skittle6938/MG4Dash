package app.mg4dash

import app.mg4dash.car.CarProps
import app.mg4dash.energy.BatteryCapacity
import app.mg4dash.energy.BatteryCapacity.Sample
import app.mg4dash.energy.ConsumptionEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Consumption from the battery %, capacity measurement, and the car's charge codes. */
class EnergyEstimatesTest {

    // ── Average consumption from the % drop ──

    @Test fun consumptionFromTheBatteryDrop() {
        // 10 % of 49 kWh over 30 km = 16.3 kWh/100 km
        assertEquals(16.33f, ConsumptionEstimator.estimate(30, 10, 49f)!!, 0.01f)
    }

    @Test fun noConsumptionOnTooShortABasis() {
        assertNull(ConsumptionEstimator.estimate(4, 10, 49f))   // under 5 km
        assertNull(ConsumptionEstimator.estimate(50, 1, 49f))   // under 2 points
        assertEquals(19.6f, ConsumptionEstimator.estimate(5, 2, 49f)!!, 0.01f)
    }

    @Test fun implausibleConsumptionIsHidden() {
        assertNull(ConsumptionEstimator.estimate(5, 20, 77f))   // 308 kWh/100
        assertNull(ConsumptionEstimator.estimate(500, 2, 49f))  // 0.2 kWh/100
    }

    // ── Capacity measured while driving ──

    @Test fun capacityFromEnergyAndDrop() {
        assertEquals(50f, BatteryCapacity.valid(10f, 20f)!!, 0.01f)
    }

    @Test fun capacityNeedsFifteenPercent() {
        assertNull(BatteryCapacity.valid(7f, BatteryCapacity.MIN_DROP - 0.1f))
        assertEquals(46.7f, BatteryCapacity.valid(7f, BatteryCapacity.MIN_DROP)!!, 0.1f)
    }

    @Test fun implausibleCapacityRejected() {
        assertNull(BatteryCapacity.valid(2.5f, 20f))   // under 3 kWh
        assertNull(BatteryCapacity.valid(25f, 20f))    // 125 kWh
        assertNull(BatteryCapacity.valid(5f, 20f))     // 25 kWh
    }

    @Test fun samplesWeightedByTheirDrop() {
        val list = listOf(Sample(0, 50f, 20), Sample(0, 60f, 60))
        assertEquals(57.5f, BatteryCapacity.weighted(list)!!, 0.01f)
        assertNull(BatteryCapacity.weighted(emptyList()))
    }

    @Test fun defaultCapacitiesOfTheModels() {
        assertEquals(listOf(51 to 49f, 64 to 61f, 77 to 72.5f), BatteryCapacity.MODELS)
    }

    // ── Charge and V2L codes sent to the car ──

    @Test fun chargeLimitCodes() {
        assertEquals(1, CarProps.targetSocCode(40))
        assertEquals(4, CarProps.targetSocCode(70))
        assertEquals(7, CarProps.targetSocCode(100))
        assertEquals(1, CarProps.targetSocCode(10))
        assertEquals(7, CarProps.targetSocCode(120))
    }

    @Test fun v2lCodesRoundTrip() {
        for (p in 20..80 step 10) assertEquals(p, CarProps.v2lPercent(CarProps.v2lCode(p)))
        assertNull(CarProps.v2lPercent(0)); assertNull(CarProps.v2lPercent(8)); assertNull(CarProps.v2lPercent(null))
    }

    @Test fun acCurrentLabels() {
        assertEquals("6 A", CarProps.currentLabel(1))
        assertEquals("Max", CarProps.currentLabel(4))
        assertEquals("—", CarProps.currentLabel(null))
    }
}
