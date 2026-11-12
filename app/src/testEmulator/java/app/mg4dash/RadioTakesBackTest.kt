package app.mg4dash

import app.mg4dash.media.MediaHub
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #36: a quick swipe radio → Bluetooth, the radio's late start taking the source back, is undone once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RadioTakesBackTest {
    private val radio = "com.saicmotor.radio"; private val bt = "com.android.bluetooth"

    @Test fun radioRetakingTheDriversChoiceIsUndoneOnce() {
        assertTrue("Bluetooth chosen, the radio back 0.8 s later", MediaHub.radioTakesBack(radio, bt, 800, retried = false))
        assertFalse("once only", MediaHub.radioTakesBack(radio, bt, 800, retried = true))
        assertFalse("3 s later: the driver's (the car's source button…)", MediaHub.radioTakesBack(radio, bt, 3_000, retried = false))
    }

    @Test fun otherCasesLeftAlone() {
        assertFalse("the radio chosen", MediaHub.radioTakesBack(radio, radio, 500, false))
        assertFalse("another source than the radio", MediaHub.radioTakesBack(bt, "com.saicmotor.media", 500, false))
        assertFalse("no choice", MediaHub.radioTakesBack(radio, null, 500, false))
        assertFalse("a projection chosen: not a car source", MediaHub.radioTakesBack(radio, "com.allgo.remoteui.mediabrowserservice", 500, false))
    }
}
