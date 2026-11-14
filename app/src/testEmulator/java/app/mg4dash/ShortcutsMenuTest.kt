package app.mg4dash

import android.content.Context
import app.mg4dash.system.Prefs
import app.mg4dash.ui.Kit
import app.mg4dash.ui.ShortcutTiles
import app.mg4dash.ui.ShortcutTiles.Tile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Shortcuts menu: the setting and its default, the tiles chosen, the cycles of the drive mode and regeneration tiles. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ShortcutsMenuTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = Prefs(ctx)

    @Before fun clear() { prefs.raw.edit().clear().commit() }

    @Test fun menuOnByDefaultAndCornerGivenBack() {
        assertTrue(prefs.shortcutsMenu)
        assertEquals(20, Kit.fogSafeDp(ctx))
        prefs.shortcutsMenu = false
        // Off: the fog light button's corner is kept free as in the last release
        assertEquals(Kit.FOG_SAFE_DP, Kit.fogSafeDp(ctx))
    }

    @Test fun firstRowByDefault() {
        assertEquals(listOf(Tile.FOG, Tile.POWER, Tile.ESP, Tile.AIDS, Tile.DEFROST, Tile.MODE), ShortcutTiles.chosen(prefs))
    }

    @Test fun choiceKeptInOrderUnknownIdsSkipped() {
        ShortcutTiles.choose(prefs, listOf(Tile.BATTERY_HEAT, Tile.FOG))
        assertEquals(listOf(Tile.BATTERY_HEAT, Tile.FOG), ShortcutTiles.chosen(prefs))
        assertEquals(listOf(Tile.FOG), ShortcutTiles.order(listOf("future_tile", "fog", "fog")))
        assertEquals(emptyList<Tile>(), ShortcutTiles.order(emptyList()))
    }

    @Test fun addedAtTheEndOrRemoved() {
        val cur = listOf(Tile.FOG, Tile.ESP)
        assertEquals(listOf(Tile.FOG, Tile.ESP, Tile.V2L), ShortcutTiles.toggled(cur, Tile.V2L))
        assertEquals(listOf(Tile.ESP), ShortcutTiles.toggled(cur, Tile.FOG))
    }

    @Test fun stateTouchedHeldUntilConfirmed() {
        val on = ShortcutTiles.Shown("On", true)
        val off = ShortcutTiles.Shown("Off", false)
        val hold = on to 4_000L
        // The car still reads the old value: the state touched stays shown
        assertEquals(on to true, ShortcutTiles.held(off, hold, 1_000))
        // The car confirms it: the hold ends
        assertEquals(on to false, ShortcutTiles.held(on, hold, 1_500))
        // Not confirmed within the hold: the car's value comes back
        assertEquals(off to false, ShortcutTiles.held(off, hold, 4_000))
        assertEquals(off to false, ShortcutTiles.held(off, null, 0))
    }

    @Test fun quickTouchesStartFromTheStateShown() {
        val off = ShortcutTiles.Shown(ctx.getString(R.string.sc_off), false)
        val once = ShortcutTiles.flipped(ctx, Tile.FOG, off)!!
        assertEquals(ShortcutTiles.Shown(ctx.getString(R.string.sc_on), true), once)
        // A second touch before the car answers goes back to off
        assertEquals(off, ShortcutTiles.flipped(ctx, Tile.FOG, once))
        // ESP: inverted when off
        val espOn = ShortcutTiles.Shown(ctx.getString(R.string.sc_active), false)
        assertEquals(ShortcutTiles.Shown(ctx.getString(R.string.sc_inactive), true), ShortcutTiles.flipped(ctx, Tile.ESP, espOn))
        // Unknown state: nothing shown in advance
        assertEquals(null, ShortcutTiles.flipped(ctx, Tile.FOG, ShortcutTiles.Shown("", false, false)))
        // Drive mode from the mode shown: eco → normal → sport in three touches
        var m: Int? = 2
        val seen = (1..3).map { ShortcutTiles.nextDriveMode(m).also { m = it } }
        assertEquals(listOf(3, 4, 2), seen)
    }

    @Test fun rearFogNeedsAutoOrDippedBeam() {
        assertEquals(true, ShortcutTiles.fogAllowed(1, false))      // switch on Auto
        assertEquals(true, ShortcutTiles.fogAllowed(0, true))       // dipped beam on
        assertEquals(false, ShortcutTiles.fogAllowed(0, false))     // lights off: greyed
        assertEquals(null, ShortcutTiles.fogAllowed(null, null))    // car silent: usable
    }

    @Test fun rearFogStillOffAfterThePressSaysToTurnTheDippedBeamOn() {
        // #38, SWI69: with the parking lights only the car ignores the press; with the dipped beam it answers in 0.34 s
        assertTrue("still off: refused", ShortcutTiles.fogRefused(0))
        assertFalse("on: accepted", ShortcutTiles.fogRefused(1))
        assertFalse("unreadable: nothing said", ShortcutTiles.fogRefused(null))
        assertTrue("read again well after the car's answer", ShortcutTiles.FOG_CHECK_MS in 1_000L..2_000L)
    }

    @Test fun onlyAClearOneFingerSlideDownPullsTheMenu() {
        val d = 1f
        assertTrue(app.mg4dash.ui.ShortcutsPanel.isPull(10f, 60f, d, 1))
        // A widget's swipe (sideways), even starting at the top: never
        assertEquals(false, app.mg4dash.ui.ShortcutsPanel.isPull(80f, 60f, d, 1))
        assertEquals(false, app.mg4dash.ui.ShortcutsPanel.isPull(10f, 30f, d, 1))   // too short
        assertEquals(false, app.mg4dash.ui.ShortcutsPanel.isPull(0f, 200f, d, 2))   // two fingers (MG4Link)
    }

    @Test fun climateHeatingOrCooling() {
        assertEquals(true, ShortcutTiles.coolingFor(true, 20f, 28f, null))     // A/C on, set point below outside: cooling
        assertEquals(false, ShortcutTiles.coolingFor(true, 22f, 9f, null))     // set point above outside: heating
        assertEquals(false, ShortcutTiles.coolingFor(false, 20f, 28f, null))   // A/C off: heating
        assertEquals(true, ShortcutTiles.coolingFor(true, 21.8f, 22f, true))   // within 0.5 °C: as before (no flicker)
        assertEquals(true, ShortcutTiles.coolingFor(true, 22f, null, null))    // no outside temperature: the A/C decides
    }

    @Test fun driveModeAndRegenCycles() {
        assertEquals(3, ShortcutTiles.nextDriveMode(2))
        assertEquals(4, ShortcutTiles.nextDriveMode(3))
        assertEquals(2, ShortcutTiles.nextDriveMode(4))
        assertEquals(3, ShortcutTiles.nextDriveMode(6))   // snow → normal
        assertEquals(3, ShortcutTiles.nextDriveMode(null))
        assertEquals(1, ShortcutTiles.nextRegen(0))
        assertEquals(0, ShortcutTiles.nextRegen(3))
        assertEquals(1, ShortcutTiles.nextRegen(6))       // one pedal → level 1
    }
}
