package app.mg4dash

import app.mg4dash.climate.ClimateDraw
import app.mg4dash.system.Prefs
import app.mg4dash.ui.ShortcutTiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** #34: the heating / cooling rule, the power that confirms it, and the card's setting. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ClimateRuleTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @Test fun acOffNeverCooling() {
        // A/C off: heating only more than 1 degree above outside, otherwise neither (the blower)
        assertEquals(1, ShortcutTiles.modeFor(false, 22f, 4f, null))
        assertNull(ShortcutTiles.modeFor(false, 19f, 31f, null))
        assertNull(ShortcutTiles.modeFor(false, 21f, 20.5f, null))
        assertNull(ShortcutTiles.modeFor(false, null, 10f, null))
        // A/C on: as before
        assertEquals(2, ShortcutTiles.modeFor(true, 19f, 31f, null))
        assertEquals(1, ShortcutTiles.modeFor(true, 22f, 4f, null))
    }

    @Test fun powerConfirms() {
        val d = ClimateDraw()
        // Off: nothing known
        assertNull(d.update(0, false, 0f))
        // Switched on: the rule alone during the grace, whatever the power
        assertNull(d.update(1_000, true, 100f))
        assertNull(d.update(20_000, true, 100f))
        // The blower only for 5 s after the grace: denied
        assertEquals(false, d.update(40_000, true, 150f))
        // Drawing: confirmed once above 500 W for 5 s, not before
        assertEquals(false, d.update(41_000, true, 2_000f))
        assertEquals(true, d.update(46_500, true, 2_000f))
        // In between (hysteresis): stays as it was, no flicker
        assertEquals(true, d.update(50_000, true, 400f))
        assertEquals(true, d.update(60_000, true, 400f))
        // Power unknown: the last state
        assertEquals(true, d.update(70_000, true, null))
        // Off again: forgotten
        assertNull(d.update(80_000, false, 0f))
    }

    @Test fun blowerOnlyShowsNeither() {
        val s = app.mg4dash.car.VehicleState(hvacOn = true, acOn = false, driverTemp = 19f, outsideTemp = 31f)
        val v = ShortcutTiles.climate(ctx, s, 1_000L, null)
        assertEquals(ShortcutTiles.NEUTRAL, v.level)
        assertNull(ShortcutTiles.climateMode(ctx, s))
    }

    @Test fun settingCarriesOver() {
        val sp = ctx.getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE)
        sp.edit().clear().commit()
        assertEquals(2, Prefs(ctx).climateFxMode)                       // new: word and animation
        sp.edit().putBoolean("climate_fx", false).commit()
        assertEquals(1, Prefs(ctx).climateFxMode)                       // old switch off: the word only
        sp.edit().putBoolean("climate_fx", true).commit()
        assertEquals(2, Prefs(ctx).climateFxMode)
        Prefs(ctx).climateFxMode = 0
        assertEquals(false, Prefs(ctx).climateIndicator)
        assertEquals(false, Prefs(ctx).climateFx)
        val s = app.mg4dash.car.VehicleState(hvacOn = true, acOn = true, driverTemp = 22f, outsideTemp = 4f)
        assertNull(ShortcutTiles.climateMode(ctx, s))                  // Off: no word on the card
        Prefs(ctx).climateFxMode = 1
        assertEquals(true, Prefs(ctx).climateIndicator)
        assertEquals(false, Prefs(ctx).climateFx)
    }
}
