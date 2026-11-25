package app.mg4dash

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import app.mg4dash.system.Prefs
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** The Simple home takes touches, its bar's buttons included, bottom or side bar (25/11: any button crashed it, #110). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class SimpleHomeTouchTest {
    private val prefs get() = Prefs(RuntimeEnvironment.getApplication())
    private fun idle() = repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }

    private fun tapEverywhere(vertical: Boolean) {
        prefs.raw.edit().putBoolean("vertical_dock", vertical).putBoolean("tutorial_shortcuts_seen", true).putInt("home_style", 1).commit()
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        val root = a.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1920, 720)
        // The bar's edges first (bottom row, left column), then the whole screen
        val points = (0 until 1920 step 60).map { it to 690 } + (0 until 720 step 45).map { 40 to it } +
            (0 until 1920 step 160).flatMap { x -> (0 until 720 step 120).map { y -> x to y } }
        for ((x, y) in points) {
            if (a.isFinishing || a.isDestroyed) break
            val t = SystemClock.uptimeMillis()
            a.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0))
            a.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0))
            idle()
            // A window or panel opened by the button: closed again, so the next touch reaches the home
            app.mg4dash.ui.Dialogs.closeAll()
            idle()
        }
        assertFalse("Simple home still there", a.isDestroyed)
    }

    @Test fun bottomBar() = tapEverywhere(vertical = false)

    @Test fun sideBar() = tapEverywhere(vertical = true)
}
