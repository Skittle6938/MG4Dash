package app.mg4dash.climate

import android.app.Activity
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.widget.TextView
import app.mg4dash.R
import app.mg4dash.car.CarBridge
import app.mg4dash.ui.Themes

/**
 * Débit d'air en mode AUTO (#65) : appui long sur AUTO pour choisir bas / moyen / haut, comme le
 * réglage de la clim d'origine ; le niveau s'affiche en trois petites barres dans le bouton.
 * Valeurs de la voiture : 0 = haut, 1 = moyen, 2 = bas.
 */
object AutoAirVolume {
    private val LABELS = listOf(R.string.cl_level_low, R.string.cl_level_medium, R.string.cl_level_high)

    /** Texte du bouton : « AUTO » suivi des barres (rien si la voiture ne donne pas le réglage). */
    fun paint(chip: TextView, level: Int?) {
        if (level !in 0..2) { if (chip.text.toString() != "AUTO") chip.text = "AUTO"; return }
        val bars = 3 - level!!   // bas = 1 barre, moyen = 2, haut = 3
        val c = chip.context
        val sb = SpannableStringBuilder("AUTO  ")
        listOf("▂", "▄", "▆").forEachIndexed { i, s ->
            val st = sb.length; sb.append(s)
            sb.setSpan(ForegroundColorSpan(Themes.color(c, if (i < bars) R.color.accent_action else R.color.text_disabled)), st, sb.length, 0)
            sb.setSpan(RelativeSizeSpan(0.8f), st, sb.length, 0)
        }
        if (chip.text.toString() != sb.toString() || chip.tag != bars) { chip.text = sb; chip.tag = bars }
    }

    fun attach(activity: Activity, chip: TextView) {
        chip.setOnLongClickListener {
            val level = CarBridge.state.value.autoAirVolume
            val labels = LABELS.map { activity.getString(R.string.cl_auto_air, activity.getString(it)) }
            app.mg4dash.ui.SourceMenu.choose(activity, chip, labels, level?.let { 2 - it } ?: -1) { i -> CarBridge.setAutoAirVolume(2 - i) }
            true
        }
    }
}
