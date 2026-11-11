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
}