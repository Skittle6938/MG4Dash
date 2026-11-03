package app.mg4dash.system

import android.content.Context
import app.mg4dash.car.CarProps
import java.util.Locale

/**
 * Display units (issue #9): km or miles, kWh/100 km or mi/kWh.
 * Auto = the car's setting (DISTANCE_UNITS_ADJUST: 1 = miles), otherwise the choice made in the settings.
 * Internal measurements stay in km and kWh/100 km; only the presentation changes.
 */
object Units {
    private const val KM_PER_MI = 1.609344f
    private const val DISTANCE_UNITS = 557904953   // 0x2140f439 DISTANCE_UNITS_ADJUST

    @Volatile private var carMiles: Boolean? = null
    @Volatile private var carRead = 0L

    fun miles(context: Context): Boolean = when (Prefs(context).units) {
        1 -> false
        2 -> true
        else -> {
            val now = System.currentTimeMillis()
            if (now - carRead > 60_000) { carRead = now; carMiles = CarProps.getInt(DISTANCE_UNITS)?.let { it == 1 } }
            carMiles == true
        }
    }

    fun distUnit(c: Context) = if (miles(c)) "mi" else "km"
    fun speedUnit(c: Context) = if (miles(c)) "mph" else "km/h"
    fun consUnit(c: Context) = if (miles(c)) "mi/kWh" else "kWh/100 km"
    /** Stacked chart: kWh per 100 km or 100 miles, in miles too, as mi/kWh do not add up. */
    fun stackUnit(c: Context) = if (miles(c)) "kWh/100 mi" else "kWh/100 km"
    fun stackValue(c: Context, per100: Float) = if (miles(c)) per100 * KM_PER_MI else per100

    /** km → displayed unit. */
    fun dist(c: Context, km: Float): Float = if (miles(c)) km / KM_PER_MI else km

    /** "284 km" / "176 mi" (integer); [decimals] for 50.9 km. */
    fun distText(c: Context, km: Float, decimals: Int = 0): String =
        String.format(Locale.getDefault(), "%.${decimals}f %s", dist(c, km), distUnit(c))

    fun speedText(c: Context, kmh: Float): String = "${Math.round(dist(c, kmh))} ${speedUnit(c)}"

    /** kWh/100 km → displayed value (mi/kWh = 62.14 / kWh/100 km). */
    fun cons(c: Context, per100: Float): Float = if (miles(c)) (100f / KM_PER_MI) / per100 else per100

    /** Pressure: kPa (car value) → text in the chosen unit (#11). */
    fun pressureText(c: Context, kpa: Int): String = when (Prefs(c).pressureUnit.let { if (it == 0) (if (miles(c)) 2 else 1) else it }) {
        2 -> String.format(Locale.getDefault(), "%.1f psi", kpa * 0.145038f)
        3 -> "$kpa kPa"
        else -> String.format(Locale.getDefault(), "%.2f bar", kpa / 100f)
    }

    fun consText(c: Context, per100: Float): String = String.format(Locale.getDefault(), "%.1f", cons(c, per100))
}
