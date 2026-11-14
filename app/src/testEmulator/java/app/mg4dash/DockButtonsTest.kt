package app.mg4dash

import app.mg4dash.system.Apps
import app.mg4dash.ui.Dock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The bars' buttons (#38): a tap opens the MG4Dash page, a long press the car's own screen, the same on the
 * vertical and the horizontal bar (both use [Dock.stockScreens]); no double tap anywhere.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DockButtonsTest {
    @Test fun longPressOpensTheCarsOwnScreen() {
        assertEquals(listOf(Apps.VEHICLE), Dock.stockScreens(Dock.Page.VEHICLE))
        assertEquals(listOf(Apps.HVAC), Dock.stockScreens(Dock.Page.CLIMATE))
        assertEquals(listOf(Apps.SETTINGS), Dock.stockScreens(Dock.Page.SETTINGS))
    }

    @Test fun firstInstalledAsTheirPages() {
        // Energy: the stock app, or its charging page on SWI165 (#60); Phone: BT phone, otherwise BT call
        assertEquals(listOf(Apps.ENERGY, Apps.ENERGY_SWI165), Dock.stockScreens(Dock.Page.ENERGY))
        assertEquals(listOf(Apps.BT_PHONE, Apps.BT_CALL), Dock.stockScreens(Dock.Page.PHONE))
    }

    @Test fun homeAndAndroidAutoKeepTheirOwnLongPress() {
        // Home: all apps; Android Auto (no page): choose its app
        assertTrue(Dock.stockScreens(Dock.Page.HOME).isEmpty())
        assertTrue(Dock.stockScreens(null).isEmpty())
        assertTrue(Dock.stockScreens(Dock.Page.APPS).isEmpty())
    }
}
