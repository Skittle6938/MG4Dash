package app.mg4dash

import app.mg4dash.media.MediaHub
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The phone back over Bluetooth during or just after Android Auto is not made the source (26/11, AVRCP pauses). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BtBackAfterProjectionTest {
    @Test fun bluetoothBackOnlyAwayFromAndroidAuto() {
        val grace = MediaHub.PROJECTION_GRACE_MS
        assertTrue("no Android Auto since start: as before", MediaHub.btBackAllowed(false, 100_000, 0))
        assertFalse("Android Auto has the phone", MediaHub.btBackAllowed(true, 100_000, 100_000))
        assertFalse("Bluetooth back before Android Auto reconnects", MediaHub.btBackAllowed(false, 100_000 + grace - 1, 100_000))
        assertTrue("long after Android Auto", MediaHub.btBackAllowed(false, 100_000 + grace, 100_000))
    }
}
