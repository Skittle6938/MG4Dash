package app.mg4dash

import app.mg4dash.ui.ShortcutsPanel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** − / + by the AUTO button: the automatic brightness shifted on five steps each way, 5 % a touch by hand. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AutoBrightnessTest {
    @Test fun autoShiftOnItsSteps() {
        assertEquals(0.2f, ShortcutsPanel.adjStepped(0f, 1), 1e-6f)
        assertEquals(-0.2f, ShortcutsPanel.adjStepped(0f, -1), 1e-6f)
        assertEquals(0.6f, ShortcutsPanel.adjStepped(0.4f, 1), 1e-6f)
        // A value set elsewhere off the steps: back on the nearest one, then moved
        assertEquals(0.4f, ShortcutsPanel.adjStepped(0.33f, 1), 1e-6f)
        // Kept within -1 to 1
        assertEquals(1f, ShortcutsPanel.adjStepped(1f, 1), 1e-6f)
        assertEquals(-1f, ShortcutsPanel.adjStepped(-0.9f, -1), 1e-6f)
        assertEquals(1f, ShortcutsPanel.adjStepped(5f, 1), 1e-6f)
    }

    @Test fun shiftShownOnTheButton() {
        assertEquals("AUTO", ShortcutsPanel.adjLabel(0f))
        assertEquals("AUTO", ShortcutsPanel.adjLabel(0.05f))
        assertEquals("AUTO +2", ShortcutsPanel.adjLabel(0.4f))
        assertEquals("AUTO −1", ShortcutsPanel.adjLabel(-0.2f))
        assertEquals("AUTO +5", ShortcutsPanel.adjLabel(1f))
    }

    @Test fun byHandFivePercentATouch() {
        assertEquals(141, ShortcutsPanel.handStepped(128, 1))
        assertEquals(115, ShortcutsPanel.handStepped(128, -1))
        assertEquals(255, ShortcutsPanel.handStepped(250, 1))
        assertEquals(0, ShortcutsPanel.handStepped(5, -1))
    }
}
