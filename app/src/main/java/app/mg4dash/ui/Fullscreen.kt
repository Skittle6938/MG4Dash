package app.mg4dash.ui

import android.app.Activity
import android.view.View
import app.mg4dash.system.Prefs

/**
 * Plein écran : masque la barre latérale et le bandeau SAIC (fenêtres système standard
 * TYPE_NAVIGATION_BAR / TYPE_STATUS_BAR de SystemUI) par le mode immersif d'Android.
 * Mode « sticky » : un glissement depuis le bord gauche ou le haut les fait réapparaître
 * quelques secondes, ce qui garde toujours un accès aux commandes d'origine.
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
}
