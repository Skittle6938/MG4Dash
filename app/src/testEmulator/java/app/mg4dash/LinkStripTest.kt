package app.mg4dash

import app.mg4dash.system.Firmware.Gen
import app.mg4dash.ui.ShortcutsPanel
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
}
