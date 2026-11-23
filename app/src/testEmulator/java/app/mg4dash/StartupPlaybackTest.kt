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

    /** MG4Radio #6: USB music with Android Auto for Waze only, phone unplugged: USB kept, not the radio. */
    @Test fun androidAutoGoneGivesBackTheCarSourceUnderIt() {
        val now = 10_000_000L
        val avail = listOf(radio, usb)
        fun under(played: Map<String, Long>, aaMedia: Long?, read: Boolean = true, av: List<String> = avail, cur: String = aa) =
            MediaHub.carSourceUnderAa(cur, played, av, now, aaMedia, read)
        assertEquals("USB playing, Waze guidance only", usb to true, under(mapOf(usb to now), null))
        assertEquals("USB paused for a Gemini answer", usb to true, under(mapOf(usb to now - 20_000), null))
        assertEquals("USB paused by the driver long ago, no AA music: back paused", usb to false, under(mapOf(usb to now - 30 * 60_000), null))
        assertNull("real Android Auto music after USB: fallback order", under(mapOf(usb to now - 20_000), now - 5_000))
        assertEquals("Android Auto music before USB played again", usb to true, under(mapOf(usb to now), now - 60_000))
        assertNull("nothing played: fallback order unchanged", under(emptyMap(), null))
        assertNull("the radio is the fallback's job", under(mapOf(radio to now), null))
        assertNull("USB not there", under(mapOf(usb to now), null, av = listOf(radio)))
        assertNull("only for Android Auto lost", under(mapOf(usb to now), null, cur = bt))
        assertEquals("the most recent", bt to true, under(mapOf(usb to now - 30_000, bt to now - 2_000), null, av = listOf(radio, usb, bt)))
        // Usages unreadable: the last minute only
        assertEquals("unreadable, played just before", usb to true, under(mapOf(usb to now - 40_000), null, read = false))
        assertNull("unreadable, played long ago", under(mapOf(usb to now - 5 * 60_000), null, read = false))
    }

    /** Leaving Android Auto while it plays: paused first, so it does not take the audio back after an announcement. */
    @Test fun androidAutoPausedWhenLeftForAnotherSource() {
        assertTrue("to USB", MediaHub.pauseAaBeforeSwitch(usb, aaPlaying = true, carPlay = false))
        assertTrue("to the radio", MediaHub.pauseAaBeforeSwitch(radio, aaPlaying = true, carPlay = false))
        assertFalse("to Android Auto itself", MediaHub.pauseAaBeforeSwitch(aa, aaPlaying = true, carPlay = false))
        assertFalse("not playing", MediaHub.pauseAaBeforeSwitch(usb, aaPlaying = false, carPlay = false))
        assertFalse("CarPlay: its key only toggles", MediaHub.pauseAaBeforeSwitch(usb, aaPlaying = true, carPlay = true))
    }

    @Test fun androidAutoUsages() {
        assertTrue("music", MediaHub.realMediaUsage(1))
        assertTrue("game", MediaHub.realMediaUsage(14))
        assertTrue("undeclared counts as music", MediaHub.realMediaUsage(0))
        assertFalse("navigation guidance (Waze)", MediaHub.realMediaUsage(12))
        assertFalse("assistant (Gemini)", MediaHub.realMediaUsage(16))
        assertFalse("voice call", MediaHub.realMediaUsage(2))
        assertFalse("sonification", MediaHub.realMediaUsage(13))
    }

    @Test fun betterSourceArrivingLater() {
        // After the 15 s of the startup, the phone over Bluetooth back: it takes over (the state kept by autoSwitch)
        assertEquals(bt, MediaHub.betterSource(listOf(bt, radio), radio) { it == bt || it == radio })
        assertNull("already on it", MediaHub.betterSource(listOf(bt, radio), bt) { true })
        assertNull("ranked lower: no switch", MediaHub.betterSource(listOf(bt, radio), bt) { it == radio })
        assertNull("current source never chosen: left only when lost", MediaHub.betterSource(listOf(bt, radio), usb) { true })
    }
}
