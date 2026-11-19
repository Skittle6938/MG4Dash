package app.mg4dash

import app.mg4dash.system.RadioDashUpdater
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Radio Dash offered only where it was validated (Marc, 19/11, discussion #111: SWI68 R46 restarted): SWI69 only. */
class RadioDashValidatedTest {
    @Test fun validatedFirmwares() {
        assertTrue(RadioDashUpdater.validatedOn("SWI69-29958-1300R70"))
        assertTrue(RadioDashUpdater.validatedOn("SWI69-12345"))
    }

    @Test fun notValidatedFirmwares() {
        // SWI69 only (Marc, 19/11): SWI68 R67 too is no longer offered
        assertFalse(RadioDashUpdater.validatedOn("SWI68-29958-1300R67"))
        assertFalse(RadioDashUpdater.validatedOn("SWI68-29958-1300R46"))
        assertFalse(RadioDashUpdater.validatedOn("SWI68-29958-1300R73"))
        assertFalse(RadioDashUpdater.validatedOn("SWI68-29958"))
        assertFalse(RadioDashUpdater.validatedOn("SWI165-1-R67"))
        assertFalse(RadioDashUpdater.validatedOn("SWI133-1"))
        assertFalse(RadioDashUpdater.validatedOn(null))
        assertFalse(RadioDashUpdater.validatedOn("?"))
    }
}
