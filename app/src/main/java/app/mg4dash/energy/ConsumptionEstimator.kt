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
 * Estimated average consumption (choice 3A): no kWh/100 data exists in carapi.
 *
 *   consumption = (battery % drop × usable capacity) / km driven × 100
 *
 * Deux compteurs :
 *  - "since the last charge": reset when the % goes up (charging);
 *  - "trip": reset at each launcher start (≈ power on).
 * Resolution limited by the whole %: the estimate is shown only after 5 km and 2 points
 * of battery used, otherwise it would be too noisy.
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
    /** kWh/100 km since the last charge, or null while the basis is insufficient. */
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

                // "Since the last charge" reference
                var refKm = sp.getInt("ref_km", -1)
                var refSoc = sp.getInt("ref_soc", -1)
                // Charge: the % goes up a few points above the lowest since the reference
                // (even followed % by %, as MG4Dash can run in standby during charging)
                val lowSoc = sp.getInt("ref_low", sp.getInt("last_soc", -1))
                if (refKm < 0 || refSoc < 0 || km < refKm || (lowSoc >= 0 && soc >= lowSoc + EnergyMonitor.RECHARGE_PCT) ||
                    soc >= refSoc + EnergyMonitor.RECHARGE_PCT) {
                    Log.i(TAG, "new reference: $km km, $soc %")
                    refKm = km; refSoc = soc
                    sp.edit().putInt("ref_km", km).putInt("ref_soc", soc).putInt("ref_low", soc).apply()
                } else if (lowSoc < 0 || soc < lowSoc) {
                    sp.edit().putInt("ref_low", soc).apply()
                }
                // Measurement by battery power if available, otherwise estimate from the battery %
                val sc = EnergyMonitor.sinceCharge.value
                _average.value = sc.per100 ?: estimate(km - refKm, refSoc - soc, capacity)
                // Diagnostic (consumption "—" on home): references at each appearance / disappearance
                val shown = _average.value != null
                if (shown != lastShown) {
                    lastShown = shown
                    Log.i(TAG, "average ${_average.value ?: "—"}: $km km (ref $refKm), $soc % (ref $refSoc, lowest $lowSoc), " +
                        "capacity $capacity kWh; since charge ${sc.km} km, ${"%.2f".format(java.util.Locale.US, sc.netKwh)} kWh net")
                }

                // Current trip
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

    internal fun estimate(km: Int, socDrop: Int, capacityKwh: Float): Float? {
        if (km < MIN_KM || socDrop < MIN_SOC_DROP) return null
        val value = socDrop / 100f * capacityKwh / km * 100f
        return value.takeIf { it in 5f..60f }
    }
}
