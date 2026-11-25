package app.mg4dash

import app.mg4dash.media.LyricsClock
import app.mg4dash.media.MediaHub
import org.junit.Assert.assertEquals
import org.junit.Test

/** The lyrics' clock (25/11: the karaoke did not move on Android Auto). */
class LyricsClockTest {
    private fun np(pos: Long, playing: Boolean = true, title: String = "Song", duration: Long = 200_000) =
        MediaHub.NowPlaying(available = true, packageName = "aa.player", title = title, artist = "Artist", playing = playing, positionMs = pos, durationMs = duration)

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
}
