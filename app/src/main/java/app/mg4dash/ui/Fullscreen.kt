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

    /**
     * La fenêtre d'appel de la voiture fait réapparaître les barres, qui restaient ensuite par-dessus l'accueil :
     * réécrire les mêmes réglages n'a pas d'effet, on les quitte un instant. Rien à faire si les barres sont déjà masquées.
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
