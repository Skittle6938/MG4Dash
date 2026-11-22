package app.mg4dash

import app.mg4dash.ui.SplashView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The startup reveal waits for the car's own boot animation (the MG logo), drawn over every window. */
class SplashBootAnimTest {
    @Test fun waitsForTheBootAnimationToEnd() {
        // The MG logo still playing: no reveal behind it
        assertFalse(SplashView.bootAnimDone("running", "0", 3_000))
        assertFalse(SplashView.bootAnimDone("running", "", 19_999))
        // Its end, either way Android tells it
        assertTrue(SplashView.bootAnimDone("stopped", "0", 0))
        assertTrue(SplashView.bootAnimDone("running", "1", 0))
        // Never readable on a firmware: nothing to wait for; stuck: 20 s at most
        assertTrue(SplashView.bootAnimDone("", "", 0))
        assertTrue(SplashView.bootAnimDone("running", "0", SplashView.BOOT_ANIM_WAIT_MS))
    }
}