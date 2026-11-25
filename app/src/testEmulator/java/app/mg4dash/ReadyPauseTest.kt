package app.mg4dash

import app.mg4dash.media.ReadyPause
import app.mg4dash.media.ReadyPause.Do
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** "Pause out of READY" (25/11). */
class ReadyPauseTest {
    /** READY then [off] reads without it; the actions taken. */
    private fun leave(r: ReadyPause, enabled: Boolean, off: Int = 5, inCall: Boolean = false): List<Do?> {
        r.step(enabled, ready = true, playing = true, inCall = false, startPlayback = 0)
        return (1..off).map { r.step(enabled, ready = false, playing = true, inCall = inCall, startPlayback = 0) }
    }

    @Test fun offByDefaultNothing() {
        assertEquals(listOf<Do?>(null, null, null, null, null), leave(ReadyPause(), enabled = false))
    }

    @Test fun pausedOnceAfterThreeSeconds() {
        val a = leave(ReadyPause(), enabled = true)
        assertEquals(listOf(null, null, Do.PAUSE, null, null), a)
    }

    @Test fun shortDropIgnored() {
        val r = ReadyPause()
        r.step(true, ready = true, playing = true, inCall = false, startPlayback = 0)
        assertNull(r.step(true, ready = false, playing = true, inCall = false, startPlayback = 0))
        assertNull(r.step(true, ready = false, playing = true, inCall = false, startPlayback = 0))
        assertNull("READY back within 2 s", r.step(true, ready = true, playing = true, inCall = false, startPlayback = 0))
        assertNull(r.step(true, ready = false, playing = true, inCall = false, startPlayback = 0))
    }

    @Test fun notDuringACallNorWhenPaused() {
        assertEquals(null, leave(ReadyPause(), enabled = true, inCall = true).firstOrNull { it != null })
        val r = ReadyPause()
        r.step(true, ready = true, playing = false, inCall = false, startPlayback = 0)
        repeat(4) { assertNull(r.step(true, ready = false, playing = false, inCall = false, startPlayback = 0)) }
    }

    @Test fun noPauseBeforeReadyWasSeen() {
        val r = ReadyPause()
        repeat(5) { assertNull(r.step(true, ready = false, playing = true, inCall = false, startPlayback = 0)) }
    }

    @Test fun readyBackResumesWhatItPaused() {
        val r = ReadyPause()
        leave(r, enabled = true)
        assertEquals(Do.RESUME, r.step(true, ready = true, playing = false, inCall = false, startPlayback = 0))
        assertNull("once", r.step(true, ready = true, playing = false, inCall = false, startPlayback = 0))
    }

    @Test fun alwaysPausedKeptPaused() {
        val r = ReadyPause()
        leave(r, enabled = true)
        assertNull(r.step(true, ready = true, playing = false, inCall = false, startPlayback = 1))
    }

    @Test fun driversChoiceKept() {
        val r = ReadyPause()
        leave(r, enabled = true)
        r.driverActed()
        assertNull(r.step(true, ready = true, playing = false, inCall = false, startPlayback = 0))
    }
}
