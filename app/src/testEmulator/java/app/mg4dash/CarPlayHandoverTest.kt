package app.mg4dash

import app.mg4dash.media.MediaHub
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CarPlay chosen while the radio has the sound: the source key alone first (the SAIC bar follows, #106), the #99
 * rescue once, only if the radio really still plays.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CarPlayHandoverTest {
    @Test fun rescueOnceWhenTheRadioStillPlays() {
        assertTrue(MediaHub.handoverRetry(taken = false, fromRadio = true, radioPlaying = true, attempt = 0))
        assertFalse("once only", MediaHub.handoverRetry(taken = false, fromRadio = true, radioPlaying = true, attempt = 1))
    }

    @Test fun noRescueWhenTaken() = assertFalse(MediaHub.handoverRetry(taken = true, fromRadio = true, radioPlaying = true, attempt = 0))

    @Test fun noRescueFromAnotherSource() = assertFalse(MediaHub.handoverRetry(taken = false, fromRadio = false, radioPlaying = true, attempt = 0))

    /** Its play key could pause a CarPlay already playing: never on doubt. */
    @Test fun noRescueWhenTheRadioIsNotPlaying() = assertFalse(MediaHub.handoverRetry(taken = false, fromRadio = true, radioPlaying = false, attempt = 0))

    /** "Source prise" from what the SAIC media service (its top bar) has, not the source MG4Dash follows (#106). */
    @Test fun takenAsTheSaicMediaServiceHasIt() {
        val aa = "com.allgo.remoteui.mediabrowserservice"
        assertTrue(MediaHub.carPlayTaken(50, "com.saicmotor.media"))
        assertTrue(MediaHub.carPlayTaken(52, null))
        assertFalse("MG4Dash follows CarPlay, the bar still on USB", MediaHub.carPlayTaken(6, aa))
        assertFalse(MediaHub.carPlayTaken(1, aa))
        // The service does not answer (newer firmwares): the source followed
        assertTrue(MediaHub.carPlayTaken(null, aa))
        assertFalse(MediaHub.carPlayTaken(null, "com.saicmotor.radio"))
    }

    /** A stick plugged in under CarPlay: the USB player starting by itself is paused, never when USB is the driver's source. */
    @Test fun usbPlayerPausedOnlyUnderCarPlay() {
        val aa = "com.allgo.remoteui.mediabrowserservice"; val usb = "com.saicmotor.media"
        assertTrue("stick plugged in, CarPlay the source", MediaHub.usbTakesTheBar(false, true, carPlay = true, pinned = aa, source = aa))
        assertTrue("first reading after the start", MediaHub.usbTakesTheBar(null, true, carPlay = true, pinned = null, source = aa))
        assertFalse("already playing: once per start of the player", MediaHub.usbTakesTheBar(true, true, carPlay = true, pinned = aa, source = aa))
        assertFalse("not playing", MediaHub.usbTakesTheBar(false, false, carPlay = true, pinned = aa, source = aa))
        assertFalse("USB chosen by the driver", MediaHub.usbTakesTheBar(false, true, carPlay = true, pinned = usb, source = aa))
        assertFalse("USB the car's source", MediaHub.usbTakesTheBar(false, true, carPlay = true, pinned = aa, source = usb))
        assertFalse("Android Auto, not CarPlay", MediaHub.usbTakesTheBar(false, true, carPlay = false, pinned = aa, source = aa))
        assertFalse("CarPlay connected, another source", MediaHub.usbTakesTheBar(false, true, carPlay = true, pinned = "com.saicmotor.radio", source = "com.saicmotor.radio"))
    }
}
