package app.mg4dash.cards

import org.junit.Assert.assertEquals
import org.junit.Test

/** The move from one lyrics line to the next (26/11): centred on the line's time, shorter for a short line. */
class LyricsScrollTest {
    private val lines = listOf(0L to "a", 10_000L to "b", 10_200L to "c", 20_000L to "d")

    @Test fun moveIsCentredOnTheLineTime() {
        assertEquals(LyricsScroll.TRANSITION_MS, LyricsScroll.duration(lines, 1))
        // Starts half the move before the line's time
        assertEquals(0, LyricsScroll.index(lines, 10_000 - LyricsScroll.TRANSITION_MS / 2 - 1))
        assertEquals(1, LyricsScroll.index(lines, 10_000 - LyricsScroll.TRANSITION_MS / 2))
    }

    @Test fun shortLineGetsAShorterMove() {
        assertEquals(200, LyricsScroll.duration(lines, 2))
        assertEquals(1, LyricsScroll.index(lines, 10_099))
        assertEquals(2, LyricsScroll.index(lines, 10_100))
    }

    @Test fun tickerWakesWhenTheNextMoveStarts() {
        assertEquals(250, LyricsScroll.untilNext(lines, 0))
        assertEquals(100, LyricsScroll.untilNext(lines, 9_675))
        assertEquals(250, LyricsScroll.untilNext(lines, 30_000))
    }

    @Test fun beforeTheFirstLine() {
        assertEquals(0, LyricsScroll.index(lines, -5_000))
    }
}
