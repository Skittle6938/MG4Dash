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
        // Reset all: both looks of the current profile only (Marc, 20/11)
        raw.edit().putString("bright_curve_light", BrightnessCurve().with(1.0, false, 0.5, 1).encode()).commit()
        SmartBrightness.reset(ctx, all = true)
        assertNull(raw.getString("bright_curve_dark", null))
        assertNull(raw.getString("bright_curve_light", null))
        Profiles.switchTo(ctx, b.id, "test")
        assertEquals(0.9, BrightnessCurve.decode(raw.getString("bright_curve_dark", null)).points.single().value, 0.0)
    }

    @Test fun targetCoversTheWholeRange() {
        // 0 % is the car's own floor, 16 of 255 (below it the screen does not change, Marc 20/11)
        val dark = BrightnessCurve().with(0.0, true, 0.0, 1).with(0.5, true, 0.0, 2)
        assertTrue(SmartBrightness.target(dark, 1.0).let { it in SmartBrightness.FLOOR..SmartBrightness.FLOOR + 15 })
        assertEquals(0.0, SmartBrightness.fromSetting(5), 0.0)
        assertEquals(SmartBrightness.FLOOR, SmartBrightness.toSetting(0.0))
        assertEquals(255, SmartBrightness.target(BrightnessCurve(), 100_000.0))
    }

    // ── The curve edited by hand (Settings) ──

    @Test fun aMovedPointStaysBetweenItsNeighbours() {
        val c = BrightnessCurve().with(1.0, true, 0.2, 1).with(2.5, false, 0.5, 2).with(4.0, false, 0.8, 3)
        val mid = c.points.first { it.level == 2.5 }
        assertEquals(0.8, c.moved(mid, 0.95, 4).points.first { it.level == 2.5 }.value, 1e-9)
        assertEquals(0.2, c.moved(mid, 0.0, 4).points.first { it.level == 2.5 }.value, 1e-9)
        assertEquals(0.6, c.moved(mid, 0.6, 4).points.first { it.level == 2.5 }.value, 1e-9)
        // The ends: free up to 0 and 100 %
        val first = c.points.first { it.level == 1.0 }
        assertEquals(0.0, c.moved(first, -1.0, 4).points.first { it.level == 1.0 }.value, 1e-9)
        val last = c.points.first { it.level == 4.0 }
        assertEquals(1.0, c.moved(last, 2.0, 4).points.first { it.level == 4.0 }.value, 1e-9)
        // A point not on the curve changes nothing
        assertEquals(c.points, c.moved(BrightnessCurve.Point(3.0, false, 0.1, 9), 0.5, 4).points)
    }

    @Test fun aPointDeletedAndTheBounds() {
        val c = BrightnessCurve().with(1.0, true, 0.2, 1).with(2.5, false, 0.5, 2).with(4.0, false, 0.8, 3)
        val mid = c.points.first { it.level == 2.5 }
        val less = c.without(mid)
        assertEquals(2, less.points.size)
        assertFalse(less.points.contains(mid))
        assertEquals(BrightnessCurve.decode(less.encode()).points, less.points)
        assertEquals(0.2 to 0.8, c.bounds(2.5, except = mid))
        assertEquals(0.2 to 0.5, c.bounds(2.0))
        assertEquals(0.0 to 0.2, c.bounds(0.5))
        assertEquals(0.8 to 1.0, c.bounds(4.5))
    }

    @Test fun theCurveIsEditedWithTheFinger() {
        var saved: BrightnessCurve? = null
        var selected = false
        val v = app.mg4dash.brightness.CurveView(ctx, BrightnessCurve(), { 2.0 }, { saved = it }, { selected = it })
        v.measure(android.view.View.MeasureSpec.makeMeasureSpec(800, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        val d = ctx.resources.displayMetrics.density
        val left = 44 * d; val right = 800 - 10 * d; val top = 10 * d; val bottom = v.measuredHeight - 30 * d
        fun x(level: Double) = (left + (right - left) * level / 5.0).toFloat()
        fun y(value: Double) = (bottom - (bottom - top) * value).toFloat()
        fun touch(action: Int, px: Float, py: Float) {
            val e = android.view.MotionEvent.obtain(0, 0, action, px, py, 0)
            v.onTouchEvent(e); e.recycle()
        }
        // A touch on the curve at a light adds a point there, the slide sets it, lifting the finger saves it
        touch(android.view.MotionEvent.ACTION_DOWN, x(2.0), y(BrightnessCurve.defaultAt(2.0)))
        assertTrue(selected)
        touch(android.view.MotionEvent.ACTION_MOVE, x(2.0), y(0.9))
        touch(android.view.MotionEvent.ACTION_UP, x(2.0), y(0.9))
        assertEquals(1, saved!!.points.size)
        assertEquals(2.0, saved!!.points[0].level, 0.02)
        assertEquals(0.9, saved!!.points[0].value, 0.02)
        // A second point brighter in the light cannot go below the first one: the curve keeps rising
        touch(android.view.MotionEvent.ACTION_DOWN, x(3.5), y(saved!!.valueAt(3.5)))
        touch(android.view.MotionEvent.ACTION_MOVE, x(3.5), y(0.1))
        touch(android.view.MotionEvent.ACTION_UP, x(3.5), y(0.1))
        assertEquals(2, saved!!.points.size)
        assertEquals(0.9, saved!!.points.first { it.level > 3 }.value, 0.02)
        // The point grabbed again moves it, it does not add one; then deleted
        touch(android.view.MotionEvent.ACTION_DOWN, x(2.0), y(0.9))
        touch(android.view.MotionEvent.ACTION_MOVE, x(2.0), y(0.5))
        touch(android.view.MotionEvent.ACTION_UP, x(2.0), y(0.5))
        assertEquals(2, saved!!.points.size)
        assertEquals(0.5, saved!!.points.first { it.level < 3 }.value, 0.02)
        v.deleteSelected()
        assertEquals(1, saved!!.points.size)
        assertFalse(selected)
        assertTrue(saved!!.points[0].level > 3)
    }

    // ── The slider beyond a neighbour (Marc, 20/11): the newest choice wins ──

    private fun rising(c: BrightnessCurve) = c.points.sortedBy { it.level }.zipWithNext().all { (a, b) -> a.value <= b.value }

    @Test fun aSliderPointAboveALighterOneRemovesIt() {
        val c = BrightnessCurve().with(1.0, true, 0.2, 1).with(3.0, false, 0.5, 2).with(4.5, false, 0.9, 3)
        val n = c.with(2.0, false, 0.7, 4)
        assertEquals(listOf(1.0, 4.5, 2.0), n.points.map { it.level })
        assertEquals(0.7, n.points.first { it.level == 2.0 }.value, 0.0)
        assertTrue(rising(n))
    }

    @Test fun aSliderPointBelowADarkerOneRemovesIt() {
        val c = BrightnessCurve().with(1.0, true, 0.2, 1).with(3.0, false, 0.5, 2).with(4.5, false, 0.9, 3)
        val n = c.with(4.0, false, 0.1, 4)
        // 1.0 (0.2) and 3.0 (0.5) are darker yet brighter: gone; 4.5 (0.9) agrees: kept
        assertEquals(listOf(4.5, 4.0), n.points.map { it.level })
        assertTrue(rising(n))
        assertTrue(n.valueAt(4.0) < 0.5)
    }

    @Test fun replaceAndCapStillWorkAndTheCurveKeepsRising() {
        var c = BrightnessCurve()
        val r = java.util.Random(7)
        repeat(200) { k ->
            c = c.with(r.nextDouble() * 5, r.nextBoolean(), r.nextDouble(), k.toLong())
            assertTrue(c.points.size <= BrightnessCurve.MAX_POINTS)
            assertTrue(rising(c))
            val s = c.points.sortedBy { it.level }
            assertTrue(s.zipWithNext().all { (a, b) -> b.level - a.level >= BrightnessCurve.REPLACE })
            val curve = (0..50).map { c.valueAt(it / 10.0) }
            assertTrue(curve.zipWithNext().all { (a, b) -> b >= a - 1e-9 })
        }
        // Agreeing points: up to the cap, the oldest goes
        var d = BrightnessCurve()
        for (k in 0 until 14) d = d.with(k * 0.35, false, k / 14.0, k.toLong())
        assertEquals(BrightnessCurve.MAX_POINTS, d.points.size)
        assertFalse(d.points.any { it.at < 2 })
        // Replacement within the radius
        assertEquals(1, BrightnessCurve().with(2.0, false, 0.3, 1).with(2.2, false, 0.6, 2).points.size)
    }
}