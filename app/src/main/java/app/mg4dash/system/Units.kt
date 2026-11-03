package app.mg4dash.system

import android.content.Context
import app.mg4dash.car.CarProps
import java.util.Locale

/**
 * Unités d'affichage (issue #9) : km ou miles, kWh/100 km ou mi/kWh.
 * Auto = réglage de la voiture (DISTANCE_UNITS_ADJUST : 1 = miles), sinon le choix fait dans les réglages.
 * Les mesures internes restent en km et kWh/100 km ; seule la présentation change.
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
    /** Graphique empilé : kWh pour 100 km ou 100 miles, en miles aussi, car des mi/kWh ne s'additionnent pas. */
    fun stackUnit(c: Context) = if (miles(c)) "kWh/100 mi" else "kWh/100 km"
    fun stackValue(c: Context, per100: Float) = if (miles(c)) per100 * KM_PER_MI else per100

    /** km → unité affichée. */
    fun dist(c: Context, km: Float): Float = if (miles(c)) km / KM_PER_MI else km

    /** « 284 km » / « 176 mi » (entier) ; [decimals] pour 50.9 km. */
    fun distText(c: Context, km: Float, decimals: Int = 0): String =
        String.format(Locale.getDefault(), "%.${decimals}f %s", dist(c, km), distUnit(c))

    fun speedText(c: Context, kmh: Float): String = "${Math.round(dist(c, kmh))} ${speedUnit(c)}"

    /** kWh/100 km → valeur affichée (mi/kWh = 62,14 / kWh/100 km). */
    fun cons(c: Context, per100: Float): Float = if (miles(c)) (100f / KM_PER_MI) / per100 else per100

    /** Pression : kPa (valeur de la voiture) → texte dans l'unité choisie (#11). */
    fun pressureText(c: Context, kpa: Int): String = when (Prefs(c).pressureUnit.let { if (it == 0) (if (miles(c)) 2 else 1) else it }) {
        2 -> String.format(Locale.getDefault(), "%.1f psi", kpa * 0.145038f)
        3 -> "$kpa kPa"
        else -> String.format(Locale.getDefault(), "%.2f bar", kpa / 100f)
    }

    fun consText(c: Context, per100: Float): String = String.format(Locale.getDefault(), "%.1f", cons(c, per100))
}
