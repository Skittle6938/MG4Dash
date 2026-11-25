package app.mg4dash

import app.mg4dash.system.RadioDashUpdater
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Radio Dash validated on the firmwares it was tested on (19/11, discussion #111: SWI68 R46 restarted): SWI69, plus
 * single revisions confirmed by a driver's compatibility report (25/11, #110). Elsewhere it is offered after a warning.
 */
class RadioDashValidatedTest {
    @Test fun validatedFirmwares() {
        assertTrue(RadioDashUpdater.validatedOn("SWI69-29958-1300R70"))
        assertTrue(RadioDashUpdater.validatedOn("SWI69-12345"))
    }

    @Test fun notValidatedFirmwares() {
        assertFalse(RadioDashUpdater.validatedOn("SWI68-29958-1300R67"))
        assertFalse(RadioDashUpdater.validatedOn("SWI68-29958-1300R46"))
        assertFalse(RadioDashUpdater.validatedOn("SWI68-29958-1300R73"))
        assertFalse(RadioDashUpdater.validatedOn("SWI68-29958"))
        assertFalse(RadioDashUpdater.validatedOn("SWI165-1-R67"))
        assertFalse(RadioDashUpdater.validatedOn("SWI133-1"))
        assertFalse(RadioDashUpdater.validatedOn(null))
        assertFalse(RadioDashUpdater.validatedOn("?"))
        // Not SWI690 or another name starting the same way
        assertFalse(RadioDashUpdater.validatedOn("SWI690-1-R70"))
    }

    @Test fun oneConfirmedRevisionAddedInOneLine() {
        val tested = listOf("SWI69", "SWI133 R30")
        assertTrue(RadioDashUpdater.validatedOn("SWI133-29958-1300R30", tested))
        assertTrue(RadioDashUpdater.validatedOn("swi133-1-r30", tested))
        assertTrue(RadioDashUpdater.validatedOn("SWI69-29958-1300R70", tested))
        // Another revision of the same generation is not confirmed
        assertFalse(RadioDashUpdater.validatedOn("SWI133-29958-1300R31", tested))
        assertFalse(RadioDashUpdater.validatedOn("SWI133-29958", tested))
        assertFalse(RadioDashUpdater.validatedOn("SWI68-29958-1300R30", tested))
    }
}
