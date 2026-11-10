package app.mg4dash

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.mg4dash.ui.HomeSlots.Layout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** A vertical swipe anywhere on the Driving card changes the card of its place, in every size; a touch on a button still works. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class DrivingSwipeTest {
    private val sp get() = RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE)

    private fun idle() = repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }

    private fun chips(v: View, out: MutableList<TextView> = mutableListOf()): List<TextView> {
        if (v is TextView && v.isClickable && v.text.isNotEmpty()) out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) chips(v.getChildAt(i), out)
        return out
    }

    /** Drag from ([x], [y]) in [box] upwards by 200 px, as a finger. */
    private fun drag(box: View, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        fun ev(action: Int, yy: Float, dt: Long) = MotionEvent.obtain(t, t + dt, action, x, yy, 0)
        box.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, y, 0))
        for (i in 1..10) box.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, y - 20f * i, 10L * i))
        box.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, y - 200f, 120))
        idle()
    }

    /** Home with [spec], the Driving card shown in [slot]: its place and the card. */
    private fun home(layout: Layout, slot: String, spec: String): Pair<ViewGroup, View> {
        sp.edit().clear().putBoolean("vertical_dock", true).putString("home_layout", layout.key).putString("slots_" + layout.key, spec)
            .putString("slot_now_${layout.key}_$slot", "driving").commit()
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        @Suppress("UNCHECKED_CAST")
        val f = (MainActivity::class.java.getDeclaredField("frames").apply { isAccessible = true }.get(act) as Map<String, Any>).getValue(slot)
        val box = f.javaClass.getDeclaredField("box").apply { isAccessible = true }.get(f) as ViewGroup
        return box to (MainActivity::class.java.getDeclaredField("driving").apply { isAccessible = true }.get(act) as View)
    }

    /** Centre of [v] in [box]'s coordinates, moved down by [dy]. */
    private fun centre(box: View, v: View, dy: Float = 0f): Pair<Float, Float> {
        val a = IntArray(2).also { v.getLocationInWindow(it) }; val b = IntArray(2).also { box.getLocationInWindow(it) }
        return (a[0] - b[0] + v.width / 2f) to (a[1] - b[1] + v.height / 2f + dy)
    }

    private fun check(layout: Layout, slot: String, spec: String) {
        fun shown() = sp.getString("slot_now_${layout.key}_$slot", null)
        // Between the buttons: the card's last quarter (below the last row in most sizes, between rows otherwise)
        home(layout, slot, spec).let { (box, card) ->
            val gap = chips(card).let { c -> c.last() }
            val (x, _) = centre(box, card)
            val bottom = IntArray(2).also { gap.getLocationInWindow(it) }[1] - IntArray(2).also { box.getLocationInWindow(it) }[1] + gap.height + 6f
            drag(box, x, bottom.coerceAtMost(box.height - 4f))
        }
        assertTrue("${layout.key}/$slot: a swipe between the buttons changes card", shown() != "driving")
        // Starting on a button
        home(layout, slot, spec).let { (box, card) ->
            val chip = chips(card).first { it.text.toString() == RuntimeEnvironment.getApplication().getString(R.string.dm_sport) }
            val (x, y) = centre(box, chip)
            drag(box, x, y)
        }
        assertTrue("${layout.key}/$slot: a swipe starting on a button changes card", shown() != "driving")
    }
    @Test fun medium() = check(Layout.FULL, "tc", "tl=climate;tc=driving,map;tr=media;bl=energy;bc=hourly,daily;br=trip,power,phone")
    @Test fun small() = check(Layout.FULL, "bc", "tl=climate;tc=map,consumption;tr=media;bl=energy;bc=driving,hourly;br=trip,power,phone")
    @Test fun tall() = check(Layout.MAP_CENTER, "c", "tl=hourly,daily;bl=climate;c=driving,map;tr=media;br=energy,trip,power,phone")
    @Test fun large() = check(Layout.SQUARE_LEFT, "g", "g=driving,map;tr=climate;br=energy,trip,power,phone,hourly,daily")
    @Test fun wide() = check(Layout.BAND_TOP, "w", "w=driving,map;tr=climate;bl=energy;bc=hourly,daily;br=trip,power,phone")

    @Test fun touchOnAButtonStillWorks() {
        sp.edit().clear().putString("home_layout", "full").putString("slots_full", "tl=climate;tc=driving,map;tr=media;bl=energy;bc=hourly,daily;br=trip,power,phone")
            .putString("slot_now_full_tc", "driving").commit()
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        val card = MainActivity::class.java.getDeclaredField("driving").apply { isAccessible = true }.get(act) as View
        chips(card).first { it.text.toString() == act.getString(R.string.dm_sport) }.performClick()
        assertEquals(4, app.mg4dash.car.CarBridge.state.value.driveMode)
        assertEquals("driving", sp.getString("slot_now_full_tc", null))
        app.mg4dash.car.CarBridge.setDriveMode(3)
    }
}
