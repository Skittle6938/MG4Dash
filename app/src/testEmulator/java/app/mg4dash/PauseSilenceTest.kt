package app.mg4dash

import app.mg4dash.media.PauseSilence
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The volume held at 0 during the startup pause (#36): back once the source is really paused, 2 s at most. */
class PauseSilenceTest {
    @Test fun backOnceTheRadioStartedAndIsPaused() {
        val s = PauseSilence(startedAt = 1_000, maxMs = 2_000)
        // Not playing yet right after the switch: the radio starts about 1 s later, the volume stays at 0
        assertFalse(s.over(1_000, playing = false))
        assertFalse(s.over(1_250, playing = false))
        // The radio starts (unheard), then the pause takes: back
        assertFalse(s.over(1_900, playing = true))
        assertTrue(s.over(2_150, playing = false))
    }

    @Test fun twoSecondsAtMost() {
        // The source never starts
        val s = PauseSilence(0, 2_000)
        assertFalse(s.over(1_999, playing = false))
        assertTrue(s.over(2_000, playing = false))
        // Or does not stop: back anyway
        val p = PauseSilence(0, 2_000)
        assertFalse(p.over(500, playing = true))
        assertTrue(p.over(2_000, playing = true))
    }
}
