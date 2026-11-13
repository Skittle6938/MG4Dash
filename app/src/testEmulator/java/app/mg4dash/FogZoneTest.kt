package app.mg4dash

import app.mg4dash.ui.Dock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #108: room kept for MG's fog light button in the top left corner, unless MG4Dash's bar is under it. */
class FogZoneTest {
    @Test fun barOnTheLeftTakesTheButton() = assertTrue(Dock.fogOnBar(vertical = true, rightSide = false))

    @Test fun rightHandDriveBarOnTheRightLeavesItOnTheCards() = assertFalse(Dock.fogOnBar(vertical = true, rightSide = true))

    @Test fun bottomBarLeavesItOnTheCards() {
        assertFalse(Dock.fogOnBar(vertical = false, rightSide = false))
        assertFalse(Dock.fogOnBar(vertical = false, rightSide = true))
    }
}
