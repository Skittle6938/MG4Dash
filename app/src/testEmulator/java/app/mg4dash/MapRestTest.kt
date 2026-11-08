package app.mg4dash

import android.os.Looper
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.TimeUnit

/** The App card with "Detached window" chosen (#98): in its card it wakes on a touch and rests after the delay, also after a round trip. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "w1920dp-h720dp-land-mdpi")
class MapRestTest {
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)

    @Test fun wakesThenRestsInItsCard() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("theme_mode", 1).putInt("home_style", 2).putString("wc_map", "map,consumption,shortcuts,web").putInt("home_widget", 0)
            .putBoolean("map_float", true).putBoolean("map_floating", false).putInt("web_wake_s", 2).commit()
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        repeat(6) { idle(200) }
        val decor = act.window.decorView
        decor.measure(android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(720, android.view.View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, 1920, 720)
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
        val awake = field("awake")
        val hole = MainActivity::class.java.getDeclaredMethod("getMapHole").apply { isAccessible = true }.invoke(act) as android.view.View
        fun tapOnTheCard() {
            val at = IntArray(2).also { hole.getLocationOnScreen(it) }
            val x = at[0] + hole.width / 2f; val y = at[1] + hole.height / 2f
            val t = android.os.SystemClock.uptimeMillis()
            act.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
            act.dispatchTouchEvent(MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0))
            idle(400)
        }
        fun check(round: String) {
            assertNull("$round: at rest first", awake.get(act))
            val window = (field("floatMap").get(act) as app.mg4dash.ui.FloatWindow).frameView
            tapOnTheCard()
            assertNotNull("$round: a touch wakes it", awake.get(act))
            // The accent outline as on the web app, on its window (the card's own is under it)
            assertNotNull("$round: outline lit", window.foreground)
            idle(2500)
            assertNull("$round: at rest after the delay", awake.get(act))
            assertNull("$round: outline off", window.foreground)
        }
        check("in its card")
        // There and back in its window over home
        MainActivity::class.java.getDeclaredMethod("detachMap").apply { isAccessible = true }.invoke(act)
        repeat(3) { idle(200) }
        val fw = field("floatMap").get(act) as app.mg4dash.ui.FloatWindow
        assertEquals(true, fw.floating)
        MainActivity::class.java.getDeclaredMethod("attachMap").apply { isAccessible = true }.invoke(act)
        repeat(3) { idle(200) }
        decor.layout(0, 0, 1920, 720)
        check("after a round trip")
    }
}
