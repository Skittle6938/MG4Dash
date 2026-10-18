package app.mg4dash.ui

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import app.mg4dash.R
import app.mg4dash.car.CarBridge
import app.mg4dash.car.VehicleState
import app.mg4dash.system.Prefs

/**
 * Calibration de la température affichée (appui long sur la consigne) : la consigne de la voiture
 * ne colle pas toujours au ressenti. Décalage de −5 à +5 °, par degré, sur l'affichage seulement.
 */
object TempCalibration {
    fun show(activity: Activity, onChange: () -> Unit) {
        val kit = Kit(activity)
        val prefs = Prefs(activity)
        val box = kit.column().apply { setPadding(kit.dp(24), kit.dp(16), kit.dp(24), kit.dp(8)) }
        val preview = kit.text("", 19f, R.color.text_sub).apply { gravity = Gravity.CENTER }
        val value = kit.text("", 30f).apply { gravity = Gravity.CENTER }
        fun refresh() {
            val o = prefs.tempOffset
            value.text = when { o > 0 -> "+$o°"; o < 0 -> "−${-o}°"; else -> "0°" }
            preview.text = activity.getString(R.string.temp_cal_preview, VehicleState.tempLabel(CarBridge.state.value.driverTemp))
        }
        fun step(d: Int) {
            prefs.tempOffset = prefs.tempOffset + d
            VehicleState.tempOffset = prefs.tempOffset
            refresh(); onChange()
        }
        val r = kit.row().apply { gravity = Gravity.CENTER }
        r.addView(kit.roundButton(R.drawable.ic_minus, 64) { step(-1) })
        r.addView(value, kit.lp(kit.dp(140), -2))
        r.addView(kit.roundButton(R.drawable.ic_plus, 64) { step(1) })
        box.addView(r)
        box.addView(preview, kit.lp(-1, -2, t = 14))
        refresh()
        AlertDialog.Builder(activity)
            .setTitle(R.string.temp_cal_title)
            .setView(box)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.temp_cal_reset) { _, _ -> step(-prefs.tempOffset) }
            .show()
    }
}
