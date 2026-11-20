package app.mg4dash

import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.util.concurrent.TimeUnit

/**
 * The climate card's settings (Marc, 20/11): a long press anywhere on the temperature's area between − and +, not only on
 * its number, opens them; − and + keep their own action.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class ClimateTouchZoneTest {
    private fun idle() = repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }

    private fun home(style: Int, layoutKey: String, layout: String, spec: String): MainActivity {
        RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit().clear()
            .putBoolean("vertical_dock", true).putBoolean("tutorial_shortcuts_seen", true).putInt("home_style", style)
            .putString(layoutKey, layout).putString("slots_$layout", spec).commit()
        return Robolectric.buildActivity(MainActivity::class.java).setup().get().also {
            idle()
            val root = it.window.decorView
            root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1920, 720)
        }
    }

    private fun rect(v: View) = IntArray(2).also { v.getLocationInWindow(it) }.let { Rect(it[0], it[1], it[0] + v.width, it[1] + v.height) }

    /** The view a touch at ([x], [y]) goes to: the deepest shown one there that takes touches. */
    private fun hit(v: View, x: Int, y: Int): View? {
        if (!v.isShown || !rect(v).contains(x, y)) return null
        if (v is ViewGroup) for (i in v.childCount - 1 downTo 0) hit(v.getChildAt(i), x, y)?.let { return it }
        return v.takeIf { it.isClickable || it.isLongClickable }
    }

    private fun check(a: MainActivity, tag: String) {
        val number = a.findViewById<View>(R.id.temp)
        val zone = number.parent as View
        val z = rect(zone); val n = rect(number)
        assertTrue("$tag: the zone ${z} larger than the number $n", z.width() > n.width() && z.height() >= n.height())
        // Points of the zone away from the number: its corners, inside
        for ((x, y) in listOf(z.left + 4 to z.top + 4, z.right - 4 to z.bottom - 4, z.left + 4 to z.bottom - 4)) {
            val v = hit(a.window.decorView, x, y)
            assertNotNull("$tag: something at $x,$y", v)
            assertTrue("$tag: a long press at $x,$y takes ${v!!.javaClass.simpleName}", v === zone || v.parent === zone)
            v.performLongClick(); idle()
            assertNotNull("$tag: the settings open", ShadowDialog.getLatestDialog()?.takeIf { it.isShowing })
            ShadowDialog.getLatestDialog().dismiss(); idle()
        }
        // − and + keep theirs
        val down = a.findViewById<View>(R.id.tempDown)
        val d = rect(down)
        assertSame("$tag: − still answers", down, hit(a.window.decorView, d.centerX(), d.centerY()))
    }

    /** The fan's levels (Marc, 20/11): held still, the settings and the level unchanged; a touch still picks a level. */
    @Test fun fanGaugeHoldAndTap() {
        val a = home(0, "home_layout", "full", "tl=climate;tc=map;tr=media;bl=energy;bc=hourly,daily;br=trip,power,phone")
        val g = a.findViewById<app.mg4dash.ui.SegmentedGauge>(R.id.fanGauge)
        fun ev(action: Int, x: Float, t: Long) = android.view.MotionEvent.obtain(0, t, action, x, g.height / 2f, 0)
        val before = g.value
        // Held still on the 8th level: the settings open, nothing picked
        var picked = -1
        val pick = g.onSegmentPick!!
        g.onSegmentPick = { picked = it; pick(it) }
        val x8 = g.paddingLeft + (g.width - g.paddingLeft - g.paddingRight) * 7.5f / g.segments
        g.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_DOWN, x8, 0))
        shadowOf(Looper.getMainLooper()).idleFor(android.view.ViewConfiguration.getLongPressTimeout() + 100L, TimeUnit.MILLISECONDS)
        g.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_UP, x8, 900))
        idle()
        assertNotNull("held: the settings", ShadowDialog.getLatestDialog()?.takeIf { it.isShowing })
        assertTrue("held: no level picked ($picked)", picked == -1)
        assertTrue("held: the level shown before (${g.value} vs $before)", g.value == before)
        ShadowDialog.getLatestDialog().dismiss(); idle()
        // A touch: the level
        val x3 = g.paddingLeft + (g.width - g.paddingLeft - g.paddingRight) * 2.5f / g.segments
        g.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_DOWN, x3, 1000))
        g.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_UP, x3, 1080))
        idle()
        assertTrue("a touch picks the 3rd level ($picked)", picked == 2)
        // A slide too, even slow
        g.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_DOWN, x3, 2000))
        g.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_MOVE, x8, 2100))
        shadowOf(Looper.getMainLooper()).idleFor(android.view.ViewConfiguration.getLongPressTimeout() + 100L, TimeUnit.MILLISECONDS)
        g.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_UP, x8, 3000))
        idle()
        assertTrue("a slide picks the 8th level ($picked)", picked == 7)
    }

    @Test fun normalCard() = check(home(0, "home_layout", "full", "tl=climate;tc=map;tr=media;bl=energy;bc=hourly,daily;br=trip,power,phone"), "full")

    // Grand affichage: the climate in a quarter, a band and a half
    @Test fun bigQuarter() = check(home(app.mg4dash.system.Prefs.STYLE_BIG, "home_layout_big", "big_quad", "tl=climate;bl=energy;tr=media;br=hourly"), "quarter")
    @Test fun bigBand() = check(home(app.mg4dash.system.Prefs.STYLE_BIG, "home_layout_big", "big_two_bands", "t=climate;b=media"), "band")
    @Test fun bigHalf() = check(home(app.mg4dash.system.Prefs.STYLE_BIG, "home_layout_big", "big_two", "l=climate;r=media"), "half")

    @Test fun mapLayout() = check(home(0, "home_layout", "map", "tl=hourly,daily;tc=energy,trip;r=map;bl=climate;bc=media"), "map")
}
