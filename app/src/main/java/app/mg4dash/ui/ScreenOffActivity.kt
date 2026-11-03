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

/**
 * Screen off (double touch on Energy in the vertical bar, issue #12): the backlight
 * is cut by the stock launcher's command. Like the stock launcher (ScreenOffView), a
 * black screen stays on top: the car does not turn the screen back on by itself, this touch
 * (or a key) does.
 */
class ScreenOffActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var off = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(View(this).apply { setBackgroundColor(Color.BLACK) })
        Fullscreen.apply(this)
        // Small delay: the double touch's finger is lifted before switching off
        handler.postDelayed({ off = CarBridge.setDisplay(false); if (!off) finish() }, 300)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) wake()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean { wake(); return true }

    /** SWI68: SystemUI's black window takes the touch; when it disappears, the screen is already back on. */
    private var covered = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) covered = true else if (covered && off) wake()
    }

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
