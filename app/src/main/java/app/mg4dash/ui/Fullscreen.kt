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
        val full = Prefs(activity).fullscreen
        // Anciens firmwares : la SystemUI SAIC laisse ses barres sur les pages qui ne sont pas l'accueil
        // (issue #28) ; le drapeau plein écran de la fenêtre, comme les lecteurs vidéo, en plus du mode immersif
        if (full) activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        else activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        decor.systemUiVisibility = if (full) {
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
