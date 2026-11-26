package app.mg4dash.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The move from one lyrics line to the next (26/11): centred on the line's time, shorter for a short line, never a jump. */
class LyricsScrollTest {
    private val lines = listOf(0L to "a", 10_000L to "b", 20_000L to "c", 20_200L to "d", 30_000L to "e")
    private val half = LyricsScroll.TRANSITION_MS / 2

    @Test fun moveIsCentredOnTheLineTime() {
        assertEquals(LyricsScroll.TRANSITION_MS, LyricsScroll.duration(lines, 1))
        assertEquals(0, LyricsScroll.index(lines, 10_000 - half - 1))
        assertEquals(1, LyricsScroll.index(lines, 10_000 - half))
    }

    @Test fun shortLineGetsShorterMovesThatNeverOverlap() {
        assertEquals(200, LyricsScroll.duration(lines, 2))
        assertEquals(200, LyricsScroll.duration(lines, 3))
        assertEquals(2, LyricsScroll.index(lines, 20_099))
        assertEquals(3, LyricsScroll.index(lines, 20_100))
    }

    @Test fun tickerWakesWhenTheNextMoveStarts() {
        assertEquals(250, LyricsScroll.untilNext(lines, 0))
        assertEquals(100, LyricsScroll.untilNext(lines, 10_000 - half - 100))
        assertEquals(250, LyricsScroll.untilNext(lines, 40_000))
    }

    @Test fun progressIsContinuous() {
        assertEquals(0f, LyricsScroll.progress(lines, 10_000 - half - 1), 0f)
        assertEquals(.5f, LyricsScroll.progress(lines, 10_000), 1e-3f)
        assertEquals(1f, LyricsScroll.progress(lines, 10_000 + half), 1e-3f)
        var last = 0f
        for (p in 0L..31_000L step 5) {
            val f = LyricsScroll.progress(lines, p)
            assertTrue("jump at $p: $last -> $f", f >= last && f - last < .1f)
            last = f
        }
        assertEquals(4f, last, 0f)
    }

    @Test fun beforeTheFirstLine() {
        assertEquals(0, LyricsScroll.index(lines, -5_000))
        assertEquals(0f, LyricsScroll.progress(lines, -5_000), 0f)
    }
}
