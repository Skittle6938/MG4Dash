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
 * Air flow in AUTO mode (#65): long press on AUTO to choose low / medium / high, like the
 * stock climate setting; the level shows as three small bars in the button.
 * Car values: 0 = high, 1 = medium, 2 = low.
 */
object AutoAirVolume {
    private val LABELS = listOf(R.string.cl_level_low, R.string.cl_level_medium, R.string.cl_level_high)

    /** Button text: "AUTO" followed by the bars (nothing if the car does not give the setting). */
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
