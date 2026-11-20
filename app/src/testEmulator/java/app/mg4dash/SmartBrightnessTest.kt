package app.mg4dash

import android.content.Context
import app.mg4dash.brightness.BrightnessCurve
import app.mg4dash.brightness.LightEstimator
import app.mg4dash.brightness.SkyForecast
import app.mg4dash.brightness.SmartBrightness
import app.mg4dash.system.Profiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.log10

/** Smart brightness (Marc, 20/11): the light estimated, the learnt curve, the storage per profile and look. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SmartBrightnessTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear(); set(y, mo - 1, d, h, mi)
    }.timeInMillis

    // ── The sun ──

    @Test fun sunAtParis() {
        // Summer solstice, solar noon at Paris (~11:51 UTC): 90 − 48.86 + 23.44 ≈ 64.6°
        assertEquals(64.6, LightEstimator.sunElevation(48.86, 2.35, utc(2026, 6, 21, 11, 51)), 0.6)
        // Winter solstice noon: ≈ 17.7°
        assertEquals(17.7, LightEstimator.sunElevation(48.86, 2.35, utc(2026, 12, 21, 11, 51)), 0.6)
        // Midnight: well below
        assertTrue(LightEstimator.sunElevation(48.86, 2.35, utc(2026, 6, 21, 23, 50)) < -10)
    }

    @Test fun lightOfTheSky() {
        assertEquals(LightEstimator.NIGHT_LUX, LightEstimator.clearSky(-12.0), 0.0)
        assertEquals(LightEstimator.HORIZON_LUX, LightEstimator.clearSky(0.0), 1.0)
        assertEquals(3.0, LightEstimator.clearSky(-6.0), 0.1)
        assertTrue(LightEstimator.clearSky(60.0) > 90_000)
        // Clouds: the forecast radiation against the clear sky
        assertEquals(0.3, LightEstimator.cloudFactor(30.0, 150.0, null), 0.01)
        assertEquals(1.0, LightEstimator.cloudFactor(30.0, null, null), 0.0)
        assertTrue(LightEstimator.cloudFactor(30.0, null, 100.0) < 0.3)
        // The lights win when they say it is dark (a tunnel at noon), never brighten the night
        assertEquals(LightEstimator.LIGHTS_LUX, LightEstimator.estimate(60.0, null, null, lightsOn = true), 0.0)
        assertEquals(LightEstimator.NIGHT_LUX, LightEstimator.estimate(-20.0, null, null, lightsOn = false), 0.0)
    }

    @Test fun offlineFallsBackToTheSun() {
        SkyForecast.set(emptyList())
        assertNull(SkyForecast.at(utc(2026, 11, 20, 12)))
        // Lux with the sun alone (clear sky)
        val noon = utc(2026, 11, 20, 11, 50)
        val clear = SmartBrightness.lightNow(noon, 48.86 to 2.35, false, null)
        assertTrue("clear sky at noon in November: $clear", clear > 20_000)
        // A forecast of heavy clouds kept: much darker
        SkyForecast.set(listOf(SkyForecast.Hour(noon - 1_800_000, 40.0, 100.0), SkyForecast.Hour(noon + 1_800_000, 40.0, 100.0)))
        val cloudy = SmartBrightness.lightNow(noon, 48.86 to 2.35, false, SkyForecast.at(noon))
        assertTrue("cloudy $cloudy < clear $clear", cloudy < clear / 3)
        // Clock not set yet (before the GPS): the lights alone
        assertEquals(LightEstimator.LIGHTS_LUX, SmartBrightness.lightNow(1_000L, 48.86 to 2.35, true, null), 0.0)
        SkyForecast.set(emptyList())
    }

    // ── The curve ──

    @Test fun defaultCurve() {
        val c = BrightnessCurve()
        assertEquals(0.15, c.valueAt(log10(10.0)), 0.03)
        assertEquals(0.35, c.valueAt(log10(400.0)), 0.03)
        assertEquals(0.70, c.valueAt(log10(10_000.0)), 0.03)
        assertEquals(1.0, c.valueAt(log10(80_000.0)), 0.03)
        assertEquals(0.15, c.valueAt(0.0), 0.03)
    }

    @Test fun learntPointFollowedNearItDefaultFarAway() {
        val c = BrightnessCurve().with(log10(400.0), lights = true, value = 0.10, at = 1)
        assertTrue("near the point ${c.valueAt(log10(400.0))}", c.valueAt(log10(400.0)) < 0.2)
        // Far from it: the default
        assertEquals(1.0, c.valueAt(log10(80_000.0)), 0.04)
    }

    @Test fun neverFallsWhenTheLightRises() {
        // Contradictory corrections: brighter at dusk than in daylight
        val c = BrightnessCurve().with(2.6, false, 0.9, 1).with(4.0, false, 0.2, 2)
        var prev = -1.0
        var x = 0.0
        while (x <= 5.0) { val v = c.valueAt(x); assertTrue("at $x: $v after $prev", v >= prev - 1e-9); prev = v; x += 0.05 }
        // And smooth: no step over 5 % between neighbours 0.05 apart
        x = 0.0
        while (x < 5.0) { val d = Math.abs(c.valueAt(x + 0.05) - c.valueAt(x)); assertTrue("step $d at $x", d < 0.05); x += 0.05 }
    }

    @Test fun nearCorrectionReplacesAndCap() {
        var c = BrightnessCurve().with(3.0, false, 0.5, 1).with(3.1, false, 0.6, 2)
        assertEquals(1, c.points.size)
        assertEquals(0.6, c.points.single().value, 0.0)
        for (i in 0 until 20) c = c.with(i * 0.31, false, 0.5, 10L + i)
        assertEquals(BrightnessCurve.MAX_POINTS, c.points.size)
        // Kept: the latest ones
        assertTrue(c.points.all { it.at >= 18 })
        // Stored and read back
        assertEquals(c.points, BrightnessCurve.decode(c.encode()).points)
        assertTrue(BrightnessCurve.decode("not json").points.isEmpty())
    }

    // ── Storage, modes, profiles ──

    @Test fun offByDefaultAndModeSwitchKeepsTheLearning() {
        listOf("mg4launcher", Profiles.FILE).forEach { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        assertFalse("the original mode by default", SmartBrightness.enabled(ctx))
        val raw = ctx.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE)
        raw.edit().putBoolean(SmartBrightness.KEY_ON, true).putString(SmartBrightness.curveKey(ctx), BrightnessCurve().with(2.0, false, 0.3, 1).encode()).commit()
        raw.edit().putBoolean(SmartBrightness.KEY_ON, false).commit()
        raw.edit().putBoolean(SmartBrightness.KEY_ON, true).commit()
        assertEquals(1, SmartBrightness.curve(ctx).points.size)
    }

    @Test fun onePerProfileAndLook() {
        listOf("mg4launcher", "mg4_sound_triggers", Profiles.FILE).forEach { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        val raw = ctx.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE)
        assertTrue(Profiles.isPersonal("bright_curve_dark") && Profiles.isPersonal("bright_curve_light") && Profiles.isPersonal(SmartBrightness.KEY_ON))
        raw.edit().putString("bright_curve_dark", BrightnessCurve().with(2.0, false, 0.3, 1).encode()).commit()
        val (b, _) = Profiles.create(ctx, "B", fromCurrent = false)
        Profiles.switchTo(ctx, b!!.id, "test")
        assertNull("B has its own (none)", raw.getString("bright_curve_dark", null))
        raw.edit().putString("bright_curve_dark", BrightnessCurve().with(4.0, false, 0.9, 1).encode()).commit()
        Profiles.switchTo(ctx, "p1", "test")
        assertEquals(0.3, BrightnessCurve.decode(raw.getString("bright_curve_dark", null)).points.single().value, 0.0)
        // Reset all: every profile's
        SmartBrightness.reset(ctx, all = true)
        assertNull(raw.getString("bright_curve_dark", null))
        Profiles.switchTo(ctx, b.id, "test")
        assertNull(raw.getString("bright_curve_dark", null))
    }

    @Test fun targetCoversTheWholeRange() {
        // 0 % is the system's own minimum (10 of 255 on the car), not a floor of ours
        val dark = BrightnessCurve().with(0.0, true, 0.0, 1).with(0.5, true, 0.0, 2)
        assertTrue(SmartBrightness.target(dark, 1.0) <= 5)
        assertEquals(255, SmartBrightness.target(BrightnessCurve(), 100_000.0))
    }
}
