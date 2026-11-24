package app.mg4dash.ui

import android.app.Activity
import app.mg4dash.R
import app.mg4dash.car.CarBridge
import app.mg4dash.car.VehicleState
import app.mg4dash.system.Prefs

/**
 * Calibration of the displayed temperature (long press on the set point): the car's set point
 * does not always match how it feels. Offset from −5 to +5 °, by degree, on the display only.
 */
object TempCalibration {
    /** The climate's settings in one window (24/11: the calibration was a menu of its own): the long press on the temperature. */
    fun show(activity: Activity, onChange: () -> Unit) =
        OptionsPopup(activity, activity.getString(R.string.clim_settings_title), R.drawable.ic_fan).also { addTo(activity, it, onChange) }.show()

    /** The fine adjustments as rows of [popup]: the displayed temperature's offset, the intermittent A/C, heating / cooling on the card. */
    fun addTo(activity: Activity, popup: OptionsPopup, onChange: () -> Unit): OptionsPopup {
        val prefs = Prefs(activity)
        val offsets = (-5..5).toList()
        fun deg(o: Int) = when { o > 0 -> "+$o°"; o < 0 -> "−${-o}°"; else -> "0°" }
        popup.choice(activity.getString(R.string.temp_cal_title),
            { activity.getString(R.string.temp_cal_preview, VehicleState.tempLabel(CarBridge.state.value.driverTemp)) },
            { offsets.map { deg(it) } }, { offsets.indexOf(prefs.tempOffset.coerceIn(-5, 5)) }) { i ->
            prefs.tempOffset = offsets[i]; VehicleState.tempOffset = prefs.tempOffset; onChange()
        }
        // Intermittent A/C (#34): how long the compressor runs, then rests
        val steps = listOf(30, 60, 90, 120, 180, 240, 300, 420, 600, 900, 1200, 1800)
        fun mmss(sec: Int) = String.format(java.util.Locale.getDefault(), "%d:%02d", sec / 60, sec % 60)
        fun near(v: Int) = steps.indices.minBy { kotlin.math.abs(steps[it] - v) }
        popup.note(activity.getString(R.string.ac_cycle_title))
            .choice(activity.getString(R.string.ac_cycle_on), null, steps.map { mmss(it) }, { near(prefs.acCycleOnSec) }) { prefs.acCycleOnSec = steps[it] }
            .choice(activity.getString(R.string.ac_cycle_off), null, steps.map { mmss(it) }, { near(prefs.acCycleOffSec) }) { prefs.acCycleOffSec = steps[it] }
        // Heating / cooling on the card (#34): none, the word only, the word and the animation
        return popup.choice(activity.getString(R.string.clim_fx_title), null,
            listOf(R.string.clim_fx_off, R.string.clim_fx_text, R.string.clim_fx_anim).map { activity.getString(it) }, { prefs.climateFxMode }) {
            prefs.climateFxMode = it; onChange()
        }
    }
}
