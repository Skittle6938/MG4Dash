package app.mg4dash

import app.mg4dash.media.MediaHub
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** CarPlay chosen while the radio has the sound (#99): asked for again once, never more. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CarPlayHandoverTest {
    @Test fun retriedOnceAfterTheRadio() {
        assertTrue(MediaHub.handoverRetry(taken = false, fromRadio = true, attempt = 0))
        assertFalse(MediaHub.handoverRetry(taken = false, fromRadio = true, attempt = 1))
    }

    @Test fun notRetriedWhenTaken() = assertFalse(MediaHub.handoverRetry(taken = true, fromRadio = true, attempt = 0))

    @Test fun notRetriedFromAnotherSource() = assertFalse(MediaHub.handoverRetry(taken = false, fromRadio = false, attempt = 0))
}
