package app.mg4dash

import app.mg4dash.system.Firmware.Gen
import app.mg4dash.ui.ShortcutsPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #113: the top strip lets touches through to MG4Link only when Android Auto reaches the top of the screen. Under the
 * car's status bar (SWI173), MG4Link never sees a slide from the top, so the strip takes it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LinkStripTest {
    @Test fun fullScreenLeftToMg4Link() = assertTrue(ShortcutsPanel.GlobalStrip.linkTakesTop(true, Gen.SWI173))

    @Test fun underTheStatusBarTheStripTakesIt() {
        assertFalse(ShortcutsPanel.GlobalStrip.linkTakesTop(false, Gen.SWI173))
        assertFalse(ShortcutsPanel.GlobalStrip.linkTakesTop(false, Gen.SWI69))
    }

    @Test fun olderMg4LinkAsBeforeExceptSwi173() {
        assertTrue(ShortcutsPanel.GlobalStrip.linkTakesTop(null, Gen.SWI69))
        assertTrue(ShortcutsPanel.GlobalStrip.linkTakesTop(null, Gen.SWI68))
        assertFalse(ShortcutsPanel.GlobalStrip.linkTakesTop(null, Gen.SWI173))
    }

    /** Under the car's status bar the strip is the bar exactly: everything that starts below it stays MG4Link's. */
    @Test fun underTheBarTheStripIsTheBar() {
        assertEquals(80, ShortcutsPanel.GlobalStrip.stripHeight(underBar = true, barPx = 80))
        assertEquals(64, ShortcutsPanel.GlobalStrip.stripHeight(underBar = true, barPx = 64))
    }

    @Test fun elsewhereTheThinStrip() {
        assertEquals(ShortcutsPanel.EDGE_PX, ShortcutsPanel.GlobalStrip.stripHeight(underBar = false, barPx = 80))
        // Bar height unknown: never more than the thin strip
        assertEquals(ShortcutsPanel.EDGE_PX, ShortcutsPanel.GlobalStrip.stripHeight(underBar = true, barPx = 0))
        assertEquals(ShortcutsPanel.EDGE_PX, ShortcutsPanel.GlobalStrip.stripHeight(underBar = true, barPx = 6))
    }

    @Test fun barHeightReadFromTheSystem() {
        val c = org.robolectric.RuntimeEnvironment.getApplication()
        val id = c.resources.getIdentifier("status_bar_height", "dimen", "android")
        assertEquals(if (id > 0) c.resources.getDimensionPixelSize(id) else 0, ShortcutsPanel.GlobalStrip.barHeight(c))
    }
}
