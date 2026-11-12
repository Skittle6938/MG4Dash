package app.mg4dash

import app.mg4dash.car.CarBridge
import org.junit.Assert.assertEquals
import org.junit.Test

/** Climate buttons shown at once when touched (#34): held until the car reads the same, 4 s at most. */
class ClimateHoldTest {
    @Test fun recirculationCycleFromTheDisplay() {
        // outside (1) → recirculated (0) → auto (2) → outside: from what is shown, a second quick touch chains
        assertEquals(0, CarBridge.nextRecirc(1))
        assertEquals(2, CarBridge.nextRecirc(0))
        assertEquals(1, CarBridge.nextRecirc(2))
        assertEquals(1, CarBridge.nextRecirc(null))
        assertEquals(2, CarBridge.nextRecirc(CarBridge.nextRecirc(1)))
    }

    @Test fun heldUntilConfirmedOrFourSeconds() {
        val held = 0 to 4_000L
        // The car still on the old mode (or a step in between): the chosen one stays shown, no flicker
        assertEquals(0 to true, CarBridge.heldRecirc(1, held, 1_000))
        assertEquals(0 to true, CarBridge.heldRecirc(2, held, 2_000))
        // Confirmed: the car's value, the hold ends
        assertEquals(0 to false, CarBridge.heldRecirc(0, held, 2_500))
        // Refused (not reached after 4 s): back to the car's real state
        assertEquals(1 to false, CarBridge.heldRecirc(1, held, 4_001))
    }

    @Test fun onOffButtonsUseTheSameHold() {
        // The same rule with 1 = on, 0 = off (power, A/C, AUTO, ECO, defrost)
        val on = 1 to 4_000L
        assertEquals(1 to true, CarBridge.heldRecirc(0, on, 500))
        assertEquals(1 to false, CarBridge.heldRecirc(1, on, 900))
        assertEquals(0 to false, CarBridge.heldRecirc(0, on, 5_000))
    }
}
