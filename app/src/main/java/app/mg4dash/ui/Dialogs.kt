package app.mg4dash.ui

import android.app.Dialog
import android.widget.TextView
import app.mg4dash.R

/**
 * Fenêtres de dialogue : l'autoradio remplace la mise en page des AlertDialog d'Android et donne au titre
 * une couleur fixe (gris foncé), illisible sur fond sombre ; on lui remet la couleur de texte du thème.
 */
object Dialogs {
    fun fix(d: Dialog): Dialog {
        val id = d.context.resources.getIdentifier("alertTitle", "id", "android")
        if (id != 0) d.findViewById<TextView>(id)?.setTextColor(Themes.color(d.context, R.color.text_value))
        return d
    }
}
