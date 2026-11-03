package app.mg4dash

import app.mg4dash.energy.V2lAuto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V2L start on plugging in (#82): charge states 0 = unplugged, 11 = adapter, 13-15 = discharging. */
class V2lAutoTest {
    @Test fun startsWhenPluggedOrAlreadyPluggedAtStartup() {
        assertTrue(V2lAuto.shouldStart(0, 11))
        assertTrue(V2lAuto.shouldStart(null, 11))
    }

    @Test fun onlyOnceWhileTheAdapterStaysPlugged() {
        assertFalse(V2lAuto.shouldStart(11, 11))
    }

    @Test fun neverAfterADischargeStoppedOrFinished() {
        for (done in 13..15) assertFalse(V2lAuto.shouldStart(done, 11))
    }

    @Test fun notForAChargingCable() {
        for (status in listOf(0, 1, 7, 10, 12, 13, null)) assertFalse(V2lAuto.shouldStart(0, status))
    }
}
