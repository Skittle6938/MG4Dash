package app.mg4dash

import app.mg4dash.media.LyricsClock
import app.mg4dash.media.MediaHub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lyrics' clock (25/11: the karaoke did not move on Android Auto). */
class LyricsClockTest {
    private fun np(pos: Long, playing: Boolean = true, title: String = "Song", duration: Long = 200_000, step: Long = 0) =
        MediaHub.NowPlaying(available = true, packageName = "aa.player", title = title, artist = "Artist", playing = playing, positionMs = pos,
            durationMs = duration, positionStepMs = step)

    @Test fun runsBetweenReports() {
        val c = LyricsClock()
        c.update(np(10_000), 1_000)
        assertEquals(12_500, c.position(3_500))
    }

    @Test fun playerWithoutPositionStillMoves() {
        val c = LyricsClock()
        c.update(np(0), 1_000)
        // Metadata or state again, the position still 0: not a seek back to the start
        c.update(np(0), 5_000)
        c.update(np(0), 9_000)
        assertEquals(10_000, c.position(11_000))
    }

    @Test fun pauseStopsAndPlayGoesOn() {
        val c = LyricsClock()
        c.update(np(0), 0)
        c.update(np(0, playing = false), 4_000)
        assertEquals(4_000, c.position(60_000))
        c.update(np(0, playing = true), 60_000)
        assertEquals(6_000, c.position(62_000))
    }

    @Test fun trackChangeStartsAgain() {
        val c = LyricsClock()
        c.update(np(0), 0)
        c.update(np(0, title = "Next"), 30_000)
        assertEquals(2_000, c.position(32_000))
    }

    @Test fun reportedPositionResyncs() {
        val c = LyricsClock()
        c.update(np(0), 0)
        c.update(np(90_000), 5_000)   // a seek, or the player's own report
        assertEquals(91_000, c.position(6_000))
        c.update(np(20_000), 7_000)   // back
        assertEquals(20_500, c.position(7_500))
    }

    @Test fun neverPastTheEnd() {
        val c = LyricsClock()
        c.update(np(199_000, duration = 200_000), 0)
        assertEquals(200_000, c.position(10_000))
    }

    @Test fun playbackStateProjected() {
        assertEquals(15_000, LyricsClock.projected(10_000, 1_000, 1f, true, 6_000))
        assertEquals("speed missing while playing", 15_000, LyricsClock.projected(10_000, 1_000, 0f, true, 6_000))
        assertEquals("paused", 10_000, LyricsClock.projected(10_000, 1_000, 1f, false, 6_000))
        assertEquals("unknown", 0, LyricsClock.projected(-1, 1_000, 1f, true, 6_000))
    }

    @Test fun lyricsAhead() {
        val c = LyricsClock()
        c.update(np(10_000), 0)
        assertEquals(10_000 + LyricsClock.LEAD_MS, c.lyricsAt(0))
    }

    @Test fun wholeSecondsNeverStepBack() {
        val c = LyricsClock()
        c.update(np(10_000, step = 1000), 0)          // really 10.6 s
        var last = c.position(0)
        // The player says 11 s at 0.4 s, 12 s at 1.4 s...: the clock (10.4 then 11.4) is behind, moved to the report
        for (i in 1..20) {
            val now = i * 1000L - 600
            c.update(np(10_000 + i * 1000L, step = 1000), now)
            val p = c.position(now)
            assertTrue("never back ($p after $last)", p >= last)
            assertTrue("within the reported second", p in (10_000 + i * 1000L) until (11_000 + i * 1000L))
            last = p
        }
        // Between reports it runs on its own, finer than the second
        assertEquals(last + 500, c.position(20_000 - 600 + 500))
    }

    @Test fun wholeSecondsKeptWhenAhead() {
        val c = LyricsClock()
        c.update(np(10_000, step = 1000), 0)
        c.update(np(10_000, step = 1000), 900)
        c.update(np(11_000, step = 1000), 1_500)      // the clock says 11.5 s, inside [11, 12): kept
        assertEquals(11_500, c.position(1_500))
    }

    @Test fun wholeSecondsSeekResyncs() {
        val c = LyricsClock()
        c.update(np(10_000, step = 1000), 0)
        c.update(np(95_000, step = 1000), 2_000)
        assertEquals(95_000, c.position(2_000))
        c.update(np(30_000, step = 1000), 3_000)
        assertEquals(30_000, c.position(3_000))
    }

    @Test fun pauseWithAReportKeepsThePlace() {
        val c = LyricsClock()
        c.update(np(10_000, step = 1000), 0)
        c.update(np(10_000, playing = false, step = 1000), 700)
        assertEquals(10_700, c.position(5_000))
    }
}
