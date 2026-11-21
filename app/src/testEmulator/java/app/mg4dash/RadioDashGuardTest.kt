package app.mg4dash

import app.mg4dash.system.RadioDashGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Radio Dash switched off for maintenance mode only beside the car's own radio (21/11, #123). */
class RadioDashGuardTest {
    @Test fun offOnlyBesideTheCarsOwnRadio() {
        // SWI68 / SWI133 / SWI165: the stock radio is another package, Radio Dash runs next to it
        assertTrue(RadioDashGuard.shouldSwitchOff(radioDash = true, radioDashEnabled = true, stockRadio = true))
        // SWI69: Radio Dash is the radio (no other one): never, or no radio at all
        assertFalse(RadioDashGuard.shouldSwitchOff(radioDash = true, radioDashEnabled = true, stockRadio = false))
        // No Radio Dash, or already off: nothing to do
        assertFalse(RadioDashGuard.shouldSwitchOff(radioDash = false, radioDashEnabled = false, stockRadio = true))
        assertFalse(RadioDashGuard.shouldSwitchOff(radioDash = true, radioDashEnabled = false, stockRadio = true))
    }

    @Test fun thePackagesAndTheMarkRescueReads() {
        assertEquals("com.saicmotor.radio", RadioDashGuard.RADIO_DASH)
        assertEquals("com.saicmotor.hmi.radio", RadioDashGuard.STOCK_RADIO)
        assertEquals("mg4dash_radio_off", RadioDashGuard.KEY)
    }
}
