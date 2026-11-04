package app.mg4dash.ui

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import app.mg4dash.car.CarBridge
import app.mg4dash.system.Firmware

/**
 * Screen off (double touch on Energy in the vertical bar, issue #12): the backlight
 * is cut by the stock launcher's command. Like the stock launcher (ScreenOffView), a
 * black screen stays on top: the car does not turn the screen back on by itself, this touch
 * (or a key) does. On old SDK firmwares (#79) SystemUI has its own black window, which takes the
 * touch and turns the screen back on: ours would stay on top, so it closes once the screen is off.
 */
class ScreenOffActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var off = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(View(this).apply { setBackgroundColor(Color.BLACK) })
        Fullscreen.apply(this)
        // Small delay: the double touch's finger is lifted before switching off
        handler.postDelayed({
            off = CarBridge.setDisplay(false)
            if (!off) finish()
            else if (Firmware.family == Firmware.Family.LEGACY) { off = false; finish() }
        }, 300)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) wake()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean { wake(); return true }

    private fun wake() {
        handler.removeCallbacksAndMessages(null)
        if (off) { CarBridge.setDisplay(true); off = false }
        finish()
    }

    override fun onDestroy() {
        if (off) CarBridge.setDisplay(true)
        super.onDestroy()
    }
}
