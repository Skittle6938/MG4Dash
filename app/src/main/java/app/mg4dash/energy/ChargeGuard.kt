package app.mg4dash.energy

import android.content.Context
import app.mg4dash.car.CarBridge
import app.mg4dash.car.CarProps
import app.mg4dash.system.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Limite de charge (envoyée à la voiture) et historique de consommation (un point par km parcouru).
 *
 * Seul l'ancien SDK applique la limite lui-même. Jusqu'à la 1.1.3, MG4Dash arrêtait aussi la charge
 * à la limite sur la MG4 Standard (chargeCtrl(2)), mais il est coupé quand la voiture est fermée :
 * la charge n'était pas arrêtée (constaté le 2026-10-18). Fonction retirée.
 */
object ChargeGuard {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _history = MutableStateFlow<List<Float>>(emptyList())
    /** Conso récente (kWh/100 km) relevée à chaque km, 60 derniers points. */
    val history: StateFlow<List<Float>> = _history

    fun start(context: Context) {
        val prefs = Prefs(context)
        val sp = prefs.raw
        _history.value = (sp.getString("cons_history", "") ?: "").split(',').mapNotNull { it.toFloatOrNull() }
        if (app.mg4dash.BuildConfig.MOCK && _history.value.isEmpty()) {
            _history.value = List(48) { i -> 15f + 3f * kotlin.math.sin(i / 5f) + (i % 7) * 0.3f }
        }
        var lastKm = sp.getInt("cons_history_km", -1)
        scope.launch {
            CarBridge.state.collect { s ->
                // Historique : un point par km
                val km = s.odometerKm ?: return@collect
                if (km != lastKm) {
                    val v = CarProps.getFloat(CarProps.ELEC_CSUMP_PERKM)?.takeIf { it in 3f..60f }
                    if (lastKm >= 0 && v != null) {
                        val list = (_history.value + v).takeLast(60)
                        _history.value = list
                        sp.edit().putString("cons_history", list.joinToString(",") { String.format(java.util.Locale.US, "%.1f", it) })
                            .putInt("cons_history_km", km).apply()
                    } else {
                        sp.edit().putInt("cons_history_km", km).apply()
                    }
                    lastKm = km
                }
            }
        }
    }

    /** Envoie la limite à la voiture (appliquée par l'ancien SDK). */
    fun setLimit(context: Context, percent: Int) {
        val p = percent.coerceIn(40, 100)
        Prefs(context).chargeLimit = p
        CarProps.setInt(CarProps.CHRG_TRGT_SOC, CarProps.targetSocCode(p), "limite de charge $p %")
    }
}
