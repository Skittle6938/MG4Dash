package app.mg4dash

import app.mg4dash.system.FogOverlay
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #113: the fog light overlay only on a SystemUI that numbers the lamp icons as the one it was built against. */
class FogOverlayFitTest {
    @Test fun swi69SystemUiFits() {
        // SWI69 R45, A9_Systemui V1.0.0.222: img_front_lamp 0x7f0800b8, img_rear_lamp 0x7f0800ba
        assertTrue(FogOverlay.idsMatch(0x7f0800b8, 0x7f0800ba))
    }

    @Test fun otherSystemUiNeverGetsTheOverlay() {
        // SWI173, V1.0.0.153: front 0x7f0800b6, rear 0x7f0800b8; the overlay left SystemUI without a front lamp,
        // a NullPointerException at each of its starts and the head unit restarting
        assertFalse(FogOverlay.idsMatch(0x7f0800b6, 0x7f0800b8))
        assertFalse("ids not found", FogOverlay.idsMatch(0, 0))
        assertFalse("one id only", FogOverlay.idsMatch(0x7f0800b8, 0))
    }
}
