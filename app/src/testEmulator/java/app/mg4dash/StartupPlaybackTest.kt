package app.mg4dash

import app.mg4dash.media.MediaHub
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The source at car start (#36): paused at once, USB not shown for the startup sound, the source after the sound. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StartupPlaybackTest {
    @Test fun pausedAtOnceNotAfterEightSeconds() {
        assertTrue("radio playing, pause wanted: at 1 s", MediaHub.pauseAtOnce(playing = true, wanted = false, wait = false, leftToPhone = false, sinceStartMs = 1_200))
        assertFalse("not in the first second (the source is still being set)", MediaHub.pauseAtOnce(true, false, false, false, 400))
        assertFalse("play wanted: left to the 8 s rule", MediaHub.pauseAtOnce(true, true, false, false, 1_200))
        assertFalse("already paused", MediaHub.pauseAtOnce(false, false, false, false, 1_200))
        assertFalse("DiAuto: the phone decides", MediaHub.pauseAtOnce(true, false, false, true, 1_200))
        assertTrue("play wanted but the startup sound plays: held paused", MediaHub.pauseAtOnce(true, true, true, false, 1_200))
    }

    @Test fun usbNotShownForTheStartupSound() {
        assertTrue("sound playing, no stick", MediaHub.usbHeldBySound(soundRecent = true, usbMounted = false))
        assertFalse("a stick is there: USB is real", MediaHub.usbHeldBySound(soundRecent = true, usbMounted = true))
        assertFalse("long after the sound: the car's source as it is", MediaHub.usbHeldBySound(soundRecent = false, usbMounted = false))
    }

    @Test fun sourceAfterTheStartupSound() {
        assertTrue("setting on, sound playing", MediaHub.waitForSound(setting = true, soundRecent = true, sinceStartMs = 5_000))
        assertFalse("setting off: as before", MediaHub.waitForSound(false, true, 5_000))
        assertFalse("no startup sound, or over: nothing waits", MediaHub.waitForSound(true, false, 5_000))
        assertFalse("30 s at most", MediaHub.waitForSound(true, true, 30_500))
    }
}
