package app.mg4dash.ui

import android.app.Activity
import android.view.View
import app.mg4dash.system.Prefs

/**
 * Full screen: hides the SAIC side bar and banner (standard SystemUI windows
 * TYPE_NAVIGATION_BAR / TYPE_STATUS_BAR) through Android's immersive mode.
 * "Sticky" mode: a swipe from the left edge or the top brings them back for
 * a few seconds, which always keeps access to the stock controls.
 */
object Fullscreen {
    @Suppress("DEPRECATION")
    fun apply(activity: Activity) {
        val decor = activity.window.decorView
        decor.systemUiVisibility = if (Prefs(activity).fullscreen) {
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN
        } else {
            View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    /**
     * The bars hidden again whatever the insets say (#93): after the SAIC phone's call window, the side bar went away
     * again but the top one stayed, while [restore] judged them hidden. Cleared, then the full screen applied anew.
     */
    @Suppress("DEPRECATION")
    fun force(activity: Activity, why: String) {
        if (!Prefs(activity).fullscreen) return
        val decor = activity.window.decorView
        val insets = decor.rootWindowInsets
        app.mg4dash.system.Log.i("Bars", "$why: top ${insets?.systemWindowInsetTop}, side ${insets?.systemWindowInsetLeft}, " +
            "flags ${Integer.toHexString(decor.systemUiVisibility)}, focus ${activity.hasWindowFocus()}: hidden again")
        decor.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        decor.post { apply(activity) }
    }

    /**
     * The car's call window brings the bars back, which then stayed over home:
     * rewriting the same settings has no effect, so leave them for a moment. Nothing to do if the bars are already hidden.
     */
    @Suppress("DEPRECATION")
    fun restore(activity: Activity) {
        if (!Prefs(activity).fullscreen) return
        val decor = activity.window.decorView
        val insets = decor.rootWindowInsets ?: return
        if (insets.systemWindowInsetTop == 0 && insets.systemWindowInsetLeft == 0) return
        decor.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        decor.post { apply(activity) }
    }
}
