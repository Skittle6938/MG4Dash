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

/**
 * Steering wheel keys on an app's player, YouTube in the web app (#36, #98): the stock launcher sends them to the
 * radio, which takes the source for a moment; MG4Dash gives it back to the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WheelKeysTest {
    private val radio = "com.saicmotor.radio"; private val web = "app.mg4dash.web"

    @Test fun onePressResumesAfterAWheelPause() {
        // Paused by the wheel 1 s ago, the page still says "playing": paused (a second press was needed to resume)
        assertFalse(MediaHub.wheelPlayingAtPress(says = true, playPause = true, pausedByWheel = true, sincePauseMs = 1_000))
        // Played again from the page itself long after: really playing, the press pauses it
        assertTrue(MediaHub.wheelPlayingAtPress(true, true, pausedByWheel = true, sincePauseMs = MediaHub.WHEEL_PAUSE_STICKS_MS + 1))
        // Not paused by the wheel: what the player says
        assertTrue(MediaHub.wheelPlayingAtPress(true, true, pausedByWheel = false, sincePauseMs = 0))
        assertFalse(MediaHub.wheelPlayingAtPress(false, true, pausedByWheel = false, sincePauseMs = 0))
        // Volume or next / previous: the player's state, the wheel pause does not count
        assertTrue(MediaHub.wheelPlayingAtPress(true, playPause = false, pausedByWheel = true, sincePauseMs = 1_000))
    }

    @Test fun playPauseTogglesOtherKeysKeepTheState() {
        assertFalse("play / pause on a playing app: paused", MediaHub.wheelReplay(playPause = true, wasPlaying = true))
        assertTrue("play / pause on a paused app: plays", MediaHub.wheelReplay(playPause = true, wasPlaying = false))
        assertTrue("next on a playing app: plays on", MediaHub.wheelReplay(playPause = false, wasPlaying = true))
        assertFalse("volume on a paused app: stays paused", MediaHub.wheelReplay(playPause = false, wasPlaying = false))
    }

    @Test fun aWheelPauseDoesNotRestartTheApp() {
        // The "play" of the source switch restarted the app a second after the pause
        assertFalse(MediaHub.wheelSwitchPlays(playPause = true, replay = false))
        assertTrue(MediaHub.wheelSwitchPlays(playPause = true, replay = true))
        assertTrue(MediaHub.wheelSwitchPlays(playPause = false, replay = true))
        assertTrue(MediaHub.wheelSwitchPlays(playPause = false, replay = false))
    }

    @Test fun theRadioRetakingTheSourceAfterAWheelPauseKeepsTheCardOnTheApp() {
        // 0.1 s after the pause the radio took the source again: the card went to the DAB, the next press to the radio
        assertEquals(web, MediaHub.wheelCardKeptOn(radio, recentBack = web))
        assertNull("no wheel key just before: the radio's", MediaHub.wheelCardKeptOn(radio, recentBack = null))
        assertNull("another source: not the radio's late start", MediaHub.wheelCardKeptOn("com.android.bluetooth", recentBack = web))
    }

    @Test fun theSilenceLastsWhileTheRadioHasTheSound() {
        // next / previous: the web app had the sound back up to 1.9 s after the key, the radio heard meanwhile
        assertNull("radio: silent on", MediaHub.wheelSilenceEndsIn(radio))
        assertEquals("the app back: sound shortly after", MediaHub.WHEEL_SILENCE_TAIL_MS, MediaHub.wheelSilenceEndsIn(web))
        assertTrue("long enough for the slowest seen (1.9 s)", MediaHub.WHEEL_SILENCE_MAX_MS >= 2_500)
        assertTrue("never more than a few seconds", MediaHub.WHEEL_SILENCE_MAX_MS <= 3_000)
    }
}
