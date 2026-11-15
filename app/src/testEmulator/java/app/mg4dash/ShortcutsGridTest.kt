package app.mg4dash

import app.mg4dash.shortcuts.Shortcuts
import app.mg4dash.ui.HomeSlots.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Shortcuts card size steps (15/11): a row first, then a column, today's grid kept at step 0. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ShortcutsGridTest {
    @Test fun stepZeroIsTodaysGrid() {
        assertEquals(4 to 1, Shortcuts.grid(Size.SMALL, 0))
        assertEquals(4 to 2, Shortcuts.grid(Size.MEDIUM, 0))
        assertEquals(4 to 3, Shortcuts.grid(Size.TALL, 0))
        assertEquals(4 to 2, Shortcuts.grid(null, 0))
    }

    @Test fun moreShortcutsAtEachStep() {
        for (size in Size.values()) {
            val counts = Shortcuts.DENSITIES.map { Shortcuts.grid(size, it).let { (c, r) -> c * r } }
            assertTrue("$size $counts", counts.zipWithNext().all { (a, b) -> b > a })
        }
        assertEquals(listOf(4, 8, 10), Shortcuts.DENSITIES.map { Shortcuts.grid(Size.SMALL, it).let { (c, r) -> c * r } })
        assertEquals(listOf(8, 12, 15), Shortcuts.DENSITIES.map { Shortcuts.grid(Size.MEDIUM, it).let { (c, r) -> c * r } })
        assertEquals(listOf(12, 16, 20), Shortcuts.DENSITIES.map { Shortcuts.grid(Size.TALL, it).let { (c, r) -> c * r } })
        // Out of range: clamped
        assertEquals(Shortcuts.grid(Size.TALL, 2), Shortcuts.grid(Size.TALL, 9))
    }

    @Test fun iconFollowsTheTile() {
        assertEquals(48, Shortcuts.iconDp(80f, 80f))
        assertEquals(88, Shortcuts.iconDp(300f, 300f))
        assertTrue(Shortcuts.iconDp(133f, 238f) > 64)
    }

    /** The icon side from the card's size equals the one of its tiles (no small first frame, 15/11). */
    @Test fun iconFromTheCardSize() {
        // Medium place, 4 × 2: tiles of (562 - 30) / 4 × (378 - 4 - 10) / 2
        assertEquals(Shortcuts.iconDp(133f, 182f), Shortcuts.tileIcon(562f, 378f, 4, 2))
        assertTrue(Shortcuts.tileIcon(562f, 378f, 4, 2) > Shortcuts.tileIcon(562f, 378f, 5, 3))
    }
}
