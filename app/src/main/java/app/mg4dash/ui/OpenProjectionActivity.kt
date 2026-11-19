package app.mg4dash.ui

import android.app.Activity
import android.os.Bundle

/**
 * The Android Auto button of Radio Dash's bars (MG4Radio #5): opens what MG4Dash's own button opens (Android Auto,
 * CarPlay, the chosen app, or the phone's projection), then goes. Not exported: Radio Dash runs as the same user.
 */
class OpenProjectionActivity : Activity() {
    /** Closed at once in maintenance mode (its onCreate went no further). */
    private var closedForMaintenance = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val leave = Runnable { if (!isFinishing) finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (app.mg4dash.system.Maintenance.closeIfOn(this)) { closedForMaintenance = true; return }
        // From the strip's two finger pull on MG4Dash's screens: the projection opened directly, as TwoFingerPull does
        if (intent.getBooleanExtra(EXTRA_DIRECT, false)) Dock.openProjection(this) { main.removeCallbacks(leave); finish() }
        else Dock.pressProjection(this) { main.removeCallbacks(leave); finish() }
        main.postDelayed(leave, 4000)
    }

    override fun onDestroy() { if (closedForMaintenance) { super.onDestroy(); return }; main.removeCallbacks(leave); super.onDestroy() }

    companion object {
        /** Open the phone's projection directly (Dock.openProjection), as the two finger pull, not the button's choice. */
        const val EXTRA_DIRECT = "direct"
    }
}
