package app.mg4dash.energy

import android.content.Context
import app.mg4dash.car.CarBridge
import app.mg4dash.system.Log
import app.mg4dash.system.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Consommation moyenne estimée (choix 3A) : aucune donnée kWh/100 n'existe dans carapi.
 *
 *   conso = (baisse du % batterie × capacité utile) / km parcourus × 100
 *
 * Deux compteurs :
 *  - « depuis la dernière recharge » : remis à zéro quand le % remonte (charge) ;
 *  - « trajet » : remis à zéro à chaque démarrage du launcher (≈ mise sous tension).
 * Résolution limitée par le % entier : l'estimation n'est affichée qu'après 5 km et 2 points
 * de batterie consommés, sinon elle serait trop bruitée.
 */
object ConsumptionEstimator {
    private const val TAG = "Consumption"
    private const val MIN_KM = 5
    private const val MIN_SOC_DROP = 2

    data class Trip(
        val distanceKm: Int = 0,
        val startedAt: Long = System.currentTimeMillis(),
        val kwhPer100: Float? = null,
    )

    private val _average = MutableStateFlow<Float?>(null)
    /** kWh/100 km depuis la dernière recharge, ou null tant que la base est insuffisante. */
    val average: StateFlow<Float?> = _average

    private val _trip = MutableStateFlow(Trip())
    val trip: StateFlow<Trip> = _trip

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tripStartKm: Int? = null
    private var tripStartSoc: Int? = null
    private var lastShown: Boolean? = null

    fun start(context: Context) {
        val prefs = Prefs(context)
        val sp = prefs.raw
        scope.launch {
            CarBridge.state.collect { s ->
                val km = s.odometerKm ?: return@collect
                val soc = s.batteryPercent ?: return@collect
                val capacity = prefs.batteryKwh

                // Référence « depuis la dernière recharge »
                var refKm = sp.getInt("ref_km", -1)
                var refSoc = sp.getInt("ref_soc", -1)
                // Recharge : le % remonte de quelques points au-dessus du plus bas depuis la référence
                // (même suivie % par %, MG4Dash pouvant tourner en veille pendant la charge)
                val lowSoc = sp.getInt("ref_low", sp.getInt("last_soc", -1))
                if (refKm < 0 || refSoc < 0 || km < refKm || (lowSoc >= 0 && soc >= lowSoc + EnergyMonitor.RECHARGE_PCT) ||
                    soc >= refSoc + EnergyMonitor.RECHARGE_PCT) {
                    Log.i(TAG, "new reference: $km km, $soc %")
                    refKm = km; refSoc = soc
                    sp.edit().putInt("ref_km", km).putInt("ref_soc", soc).putInt("ref_low", soc).apply()
                } else if (lowSoc < 0 || soc < lowSoc) {
                    sp.edit().putInt("ref_low", soc).apply()
                }
                // Mesure par puissance batterie si disponible, sinon estimation par le % batterie
                val sc = EnergyMonitor.sinceCharge.value
                _average.value = sc.per100 ?: estimate(km - refKm, refSoc - soc, capacity)
                // Diagnostic (conso « — » sur l'accueil) : références à chaque apparition / disparition
                val shown = _average.value != null
                if (shown != lastShown) {
                    lastShown = shown
                    Log.i(TAG, "average ${_average.value ?: "—"}: $km km (ref $refKm), $soc % (ref $refSoc, lowest $lowSoc), " +
                        "capacity $capacity kWh; since charge ${sc.km} km, ${"%.2f".format(java.util.Locale.US, sc.netKwh)} kWh net")
                }

                // Trajet en cours
                if (tripStartKm == null) { tripStartKm = km; tripStartSoc = soc }
                if (soc > (tripStartSoc ?: soc) + 1) tripStartSoc = soc
                val d = km - (tripStartKm ?: km)
                _trip.value = _trip.value.copy(
                    distanceKm = d,
                    kwhPer100 = EnergyMonitor.sinceStart.value.per100 ?: estimate(d, (tripStartSoc ?: soc) - soc, capacity),
                )
            }
        }
    }

    private fun estimate(km: Int, socDrop: Int, capacityKwh: Float): Float? {
        if (km < MIN_KM || socDrop < MIN_SOC_DROP) return null
        val value = socDrop / 100f * capacityKwh / km * 100f
        return value.takeIf { it in 5f..60f }
    }
}
