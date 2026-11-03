package app.mg4dash.ui

import android.text.TextUtils
import android.widget.ImageView
import android.widget.TextView
import app.mg4dash.R

/*
 * Android redessine une vue qu'on réécrit à l'identique, et refait la mise en page de l'écran quand sa largeur suit
 * le texte. L'accueil relit ses valeurs chaque seconde : sans ces gardes, il se redessinait sans que rien ne change.
 */

/** Texte, seulement s'il a changé (les styles du texte ne sont pas comparés). */
fun TextView.put(value: CharSequence?) {
    if (!TextUtils.equals(text, value ?: "")) text = value
}

/** Image, seulement si elle a changé ; à utiliser pour toutes les écritures de cette vue. */
fun ImageView.putImage(res: Int) {
    if (getTag(R.id.tag_shown_image) == res) return
    setTag(R.id.tag_shown_image, res)
    setImageResource(res)
}

/** Teinte, seulement si elle a changé ; à utiliser pour toutes les écritures de cette vue. */
fun ImageView.putTint(color: Int) {
    if (getTag(R.id.tag_shown_tint) == color) return
    setTag(R.id.tag_shown_tint, color)
    setColorFilter(color)
}
