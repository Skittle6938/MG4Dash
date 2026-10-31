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
        // A/C intermittent (#34) : durées de marche et d'arrêt, réglées par 30 s
        box.addView(kit.text(activity.getString(R.string.ac_cycle_title), 18f, R.color.text_title), kit.lp(-1, -2, t = 22))
        fun mmss(sec: Int) = String.format(java.util.Locale.getDefault(), "%d:%02d", sec / 60, sec % 60)
        fun durRow(label: Int, get: () -> Int, set: (Int) -> Unit) {
            val row = kit.row().apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(kit.text(activity.getString(label), 18f), kit.lp(0, -2, 1f))
            val v = kit.text(mmss(get()), 22f).apply { gravity = Gravity.CENTER }
            row.addView(kit.roundButton(R.drawable.ic_minus, 52) { set(get() - 30); v.text = mmss(get()) })
            row.addView(v, kit.lp(kit.dp(100), -2))
            row.addView(kit.roundButton(R.drawable.ic_plus, 52) { set(get() + 30); v.text = mmss(get()) })
            box.addView(row, kit.lp(-1, -2, t = 8))
        }
        durRow(R.string.ac_cycle_on, { prefs.acCycleOnSec }) { prefs.acCycleOnSec = it }
        durRow(R.string.ac_cycle_off, { prefs.acCycleOffSec }) { prefs.acCycleOffSec = it }
        refresh()
        AlertDialog.Builder(activity)
            .setTitle(R.string.temp_cal_title)
            .setView(box)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.temp_cal_reset) { _, _ -> step(-prefs.tempOffset) }
            .show().also { app.mg4dash.ui.Dialogs.fix(it) }
    }
}
