package app.mg4dash

import app.mg4dash.media.MediaHub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Startup and normal operation around the startup sequence (#36): what MediaHub decides itself. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StartupPlaybackTest {
    private val aa = "com.allgo.remoteui.mediabrowserservice"
    private val radio = "com.saicmotor.radio"; private val bt = "com.android.bluetooth"; private val usb = "com.saicmotor.media"

    @Test fun usbHeldWhileTheStartupSoundPlays() {
        assertTrue("sound playing, no stick", MediaHub.usbHeldBySound(soundRecent = true, usbMounted = false))
        assertFalse("a stick is there: USB is real", MediaHub.usbHeldBySound(soundRecent = true, usbMounted = true))
        assertFalse("long after the sound: the car's source as it is", MediaHub.usbHeldBySound(soundRecent = false, usbMounted = false))
    }

    @Test fun restartWhileDrivingHasNoSequence() {
        assertTrue("head unit on 30 s ago: a car start", MediaHub.isCarStart(30_000))
        assertFalse("on for 6 min: MG4Dash restarted while driving (update, crash), nothing done", MediaHub.isCarStart(6 * 60_000))
    }

    @Test fun sourceLostWhileDriving() {
        assertEquals("the first of the order there", radio, MediaHub.replacement(listOf(bt, usb, radio), bt, listOf(radio), carPlay = false))
        assertEquals(usb, MediaHub.replacement(listOf(bt, usb, radio), bt, listOf(usb, radio), carPlay = false))
    }

    @Test fun carPlayNeverChosenAutomatically() {
        assertEquals("CarPlay skipped", radio, MediaHub.replacement(listOf(bt, aa, radio), bt, listOf(aa, radio), carPlay = true))
        assertEquals("wired Android Auto may be", aa, MediaHub.replacement(listOf(bt, aa, radio), bt, listOf(aa, radio), carPlay = false))
    }

    @Test fun betterSourceArrivingLater() {
        // After the 15 s of the startup, the phone over Bluetooth back: it takes over (the state kept by autoSwitch)
        assertEquals(bt, MediaHub.betterSource(listOf(bt, radio), radio) { it == bt || it == radio })
        assertNull("already on it", MediaHub.betterSource(listOf(bt, radio), bt) { true })
        assertNull("ranked lower: no switch", MediaHub.betterSource(listOf(bt, radio), bt) { it == radio })
        assertNull("current source never chosen: left only when lost", MediaHub.betterSource(listOf(bt, radio), usb) { true })
    }
}
