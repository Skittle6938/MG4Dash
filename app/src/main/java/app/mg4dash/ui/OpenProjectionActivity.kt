package app.mg4dash.ui

import android.app.Activity
import android.os.Bundle

/**
 * The Android Auto button of Radio Dash's bars (MG4Radio #5): opens what MG4Dash's own button opens (Android Auto,
 * CarPlay, the chosen app, or the phone's projection), then goes. Not exported: Radio Dash runs as the same user.
 */
class OpenProjectionActivity : Activity() {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val leave = Runnable { if (!isFinishing) finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Dock.pressProjection(this) { main.removeCallbacks(leave); finish() }
        main.postDelayed(leave, 4000)
    }

    override fun onDestroy() { main.removeCallbacks(leave); super.onDestroy() }
}
