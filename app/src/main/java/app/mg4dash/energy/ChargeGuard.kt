package app.mg4dash.energy

import android.content.Context
import app.mg4dash.car.CarProps
import app.mg4dash.system.Prefs

/**
 * Charge limit (sent to the car). Consumption curves are in [ConsumptionCurve].
 *
 * Only the old SDK applies the limit itself. Up to 1.1.3, MG4Dash also stopped charging
 * at the limit on the MG4 Standard (chargeCtrl(2)), but it is cut when the car is closed:
 * charging was not stopped (found on 2026-10-18). Feature removed.
 */
object ChargeGuard {

    /** Sends the limit to the car (applied by the old SDK). */
    fun setLimit(context: Context, percent: Int) {
        val p = percent.coerceIn(40, 100)
        Prefs(context).chargeLimit = p
        CarProps.setInt(CarProps.CHRG_TRGT_SOC, CarProps.targetSocCode(p), "charge limit $p %")
    }
}
