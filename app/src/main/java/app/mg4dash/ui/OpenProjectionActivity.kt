package app.mg4dash.ui

import android.app.Activity
import android.os.Bundle
import app.mg4dash.system.Maintenance

/**
 * The Android Auto button of Radio Dash's bars (MG4Radio #5): opens what MG4Dash's own button opens (Android Auto,
 * CarPlay, the chosen app, or the phone's projection), then goes. Not exported: Radio Dash runs as the same user.
 */
class OpenProjectionActivity : Activity() {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val leave = Runnable { if (!isFinishing) finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A radio shortcut can reach this activity while the stock launcher is in use.
        if (Maintenance.on(this)) {
            finish()
            overridePendingTransition(0, 0)
            return
        }
        // EXTRA_DIRECT: the projection opened directly, as the edge swipe of MG4Dash's screens does (EdgeSwipe)
        if (intent.getBooleanExtra(EXTRA_DIRECT, false)) Dock.openProjection(this) { main.removeCallbacks(leave); finish() }
        else Dock.pressProjection(this) { main.removeCallbacks(leave); finish() }
        main.postDelayed(leave, 4000)
    }

    override fun onDestroy() {
        main.removeCallbacks(leave)
        super.onDestroy()
    }

    companion object {
        /** Open the phone's projection directly (Dock.openProjection), as the edge swipe, not the button's choice. */
        const val EXTRA_DIRECT = "direct"
    }
}
