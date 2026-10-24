package app.mg4dash.energy

import android.content.Context
import app.mg4dash.car.CarProps
import app.mg4dash.system.Prefs

/**
 * Limite de charge (envoyée à la voiture). Les courbes de consommation sont dans [ConsumptionCurve].
 *
 * Seul l'ancien SDK applique la limite lui-même. Jusqu'à la 1.1.3, MG4Dash arrêtait aussi la charge
 * à la limite sur la MG4 Standard (chargeCtrl(2)), mais il est coupé quand la voiture est fermée :
 * la charge n'était pas arrêtée (constaté le 2026-10-18). Fonction retirée.
 */
object ChargeGuard {

    /** Envoie la limite à la voiture (appliquée par l'ancien SDK). */
    fun setLimit(context: Context, percent: Int) {
        val p = percent.coerceIn(40, 100)
        Prefs(context).chargeLimit = p
        CarProps.setInt(CarProps.CHRG_TRGT_SOC, CarProps.targetSocCode(p), "limite de charge $p %")
    }
}
