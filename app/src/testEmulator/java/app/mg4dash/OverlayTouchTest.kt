package app.mg4dash

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.TimeUnit

/** #26: the Keep climate panel's buttons over the map and web app cards get their taps; dialogs open without the bar. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class OverlayTouchTest {
    private fun idle(n: Int = 5) = repeat(n) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }

    private fun all(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)

    private fun rect(v: View): android.graphics.Rect { val l = IntArray(2); v.getLocationInWindow(l); return android.graphics.Rect(l[0], l[1], l[0] + v.width, l[1] + v.height) }

    @Test fun keepClimateButtonsOverTheMapCard() {
        RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putBoolean("vertical_dock", true).putBoolean("tutorial_shortcuts_seen", true).putInt("theme_mode", 1).commit()
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        app.mg4dash.climate.ClimateHoldPanel.show(act)
        idle()
        assertTrue(act.panelOver())
        // The map card's place, and a clickable view of the panel over it
        @Suppress("UNCHECKED_CAST")
        val panels = MainActivity::class.java.getDeclaredField("panels").apply { isAccessible = true }.get(act) as Map<Any, View>
        val map = rect(panels.entries.first { it.key.toString() == "MAP" }.value)
        val content = act.findViewById<ViewGroup>(android.R.id.content)
        val shade = content.getChildAt(content.childCount - 1)
        val button = all(shade).drop(1).filter { it.isClickable && it.isShown && it.width > 0 }
            .first { val r = rect(it); map.contains(r.centerX(), r.centerY()) }
        var clicks = 0
        button.setOnClickListener { clicks++ }
        val r = rect(button)
        val x = r.exactCenterX(); val y = r.exactCenterY()
        val t = SystemClock.uptimeMillis()
        act.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        act.dispatchTouchEvent(MotionEvent.obtain(t, t + 80, MotionEvent.ACTION_UP, x, y, 0))
        idle(2)
        assertEquals("the panel's button over the map card gets the tap", 1, clicks)
    }

    @Suppress("DEPRECATION")
    @Test fun dialogOpensWithTheBarsHidden() {
        RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putBoolean("tutorial_shortcuts_seen", true).commit()
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle(3)
        val d = android.app.AlertDialog.Builder(act).setMessage("x").create()
        app.mg4dash.ui.Dialogs.show(d)
        idle(2)
        val w = d.window!!
        assertEquals(View.SYSTEM_UI_FLAG_FULLSCREEN, w.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN)
        // It takes the focus back once open (typing, keys)
        assertEquals(0, w.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    }
}
