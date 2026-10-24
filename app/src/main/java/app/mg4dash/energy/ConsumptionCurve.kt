package app.mg4dash.energy

import android.content.Context
import android.util.Log
import app.mg4dash.BuildConfig
import app.mg4dash.car.CarBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Courbes de consommation (#44), à partir de l'énergie mesurée par [EnergyMonitor] (tension × courant,
 * récupération déduite) plutôt que de la conso instantanée de la voiture, trop bruitée :
 *  - trajet en cours : un point tous les 0,5 km (kWh nets × 100 / km), et à l'arrêt voiture allumée un
 *    point par minute, compté comme 0,5 km ; vidée à chaque démarrage ;
 *  - depuis la recharge : les points de tous les trajets depuis la dernière recharge ;
 *  - 12 derniers mois : un point par trajet (sa moyenne), à partir de 2 km.
 */
object ConsumptionCurve {
    private const val TAG = "ConsCurve"
    private const val MAX_POINTS = 1000
    /** Un point tous les 0,5 km (#44), distance tirée de la vitesse ; à l'arrêt une minute compte pour autant. */
    private const val STEP_KM = 0.5f
    private const val YEAR_MS = 365L * 24 * 3600 * 1000

    data class Curves(
        val trip: List<Float> = emptyList(),
        val charge: List<Float> = emptyList(),
        /** Moyenne de chaque trajet des 12 derniers mois (kWh/100), du plus ancien au plus récent. */
        val trips: List<Float> = emptyList(),
    ) {
        /** Courbe de la période choisie : 0 = depuis la recharge, 1 = trajet, 2 = 12 mois. */
        fun of(period: Int) = when (period) { 1 -> trip; 2 -> trips; else -> charge }
    }

    /** Effacement demandé depuis l'écran : 0 = depuis la recharge, 1 = trajet, 2 = 12 mois ; -1 = rien. */
    @Volatile private var clearRequest = -1
    fun clear(period: Int) { clearRequest = period }

    private val _curves = MutableStateFlow(Curves())
    val curves: StateFlow<Curves> = _curves

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun parse(s: String?) = (s ?: "").split(',').mapNotNull { it.toFloatOrNull() }
    private fun join(l: List<Float>) = l.joinToString(",") { String.format(java.util.Locale.US, "%.1f", it) }

    fun start(context: Context) {
        if (BuildConfig.MOCK) {
            val trip = List(36) { i -> 15.5f + 2.2f * kotlin.math.sin(i / 6f) + (if (i % 9 == 4) 1.2f else 0f) }
            _curves.value = Curves(trip, List(14) { 16f + it % 4 * 0.6f } + trip, List(40) { i -> 16f + 3f * kotlin.math.sin(i / 7f) + (i % 5) * 0.4f })
            return
        }
        val sp = context.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE)
        // Trajets de l'année : « horodatage:kWh/100 » séparés par « ; »
        val trips = (sp.getString("cc_trips", "") ?: "").split(';').mapNotNull { e ->
            val p = e.split(':'); if (p.size == 2) p[0].toLongOrNull()?.let { t -> p[1].toFloatOrNull()?.let { t to it } } else null
        }.toMutableList()
        var trip = parse(sp.getString("cc_trip", "")).takeLast(MAX_POINTS)
        var charge = parse(sp.getString("cc_charge", "")).takeLast(MAX_POINTS)
        // 12 mois : les trajets finis, plus le trajet en cours dès 2 km (#44)
        fun publish() {
            val now = EnergyMonitor.sinceStart.value.per100
            _curves.value = Curves(trip, charge, trips.map { it.second } + listOfNotNull(now))
        }
        publish()

        scope.launch {
            var seq = -1
            var lastKm = -1
            var lastNet = 0f
            var stillSince = android.os.SystemClock.elapsedRealtime()
            var lastStart = EnergyMonitor.Totals()
            var lastChargeOut = EnergyMonitor.sinceCharge.value.outKwh
            var lastTick = android.os.SystemClock.elapsedRealtime()
            var dist = 0f   // km parcourus depuis le dernier point (vitesse intégrée)
            while (true) {
                delay(1000)
                val start = EnergyMonitor.sinceStart.value
                val tick = android.os.SystemClock.elapsedRealtime()
                var changed = false
                // Nouveau démarrage : moyenne du trajet fini gardée pour l'année, courbe du trajet vidée
                if (EnergyMonitor.tripSeq != seq) {
                    if (seq >= 0) lastStart.per100?.let { avg ->
                        val now = System.currentTimeMillis()
                        trips.add(now to avg)
                        trips.removeAll { now - it.first > YEAR_MS }
                        Log.i(TAG, "trip ended: ${lastStart.km} km, ${"%.1f".format(java.util.Locale.US, avg)} kWh/100")
                    }
                    seq = EnergyMonitor.tripSeq
                    trip = emptyList(); lastKm = start.km; lastNet = start.netKwh; stillSince = tick; dist = 0f
                    changed = true
                }
                // Recharge : les kWh depuis la recharge repartent de zéro
                val chargeOut = EnergyMonitor.sinceCharge.value.outKwh
                if (chargeOut + 0.05f < lastChargeOut) { charge = emptyList(); changed = true }
                lastChargeOut = chargeOut
                lastStart = start

                // Effacement demandé (#44)
                when (clearRequest) {
                    0 -> charge = emptyList()
                    1 -> trip = emptyList()
                    2 -> trips.clear()
                }
                if (clearRequest >= 0) { clearRequest = -1; changed = true }
                val net = start.netKwh
                val plugged = app.mg4dash.car.VehicleState.isPlugged(CarBridge.state.value.chargeStatus)
                val speed = CarBridge.state.value.speedKmh
                val dt = (tick - lastTick).coerceIn(0L, 5000L)
                lastTick = tick
                if (speed != null && speed > 0f) dist += speed * dt / 3_600_000f
                val point: Float? = when {
                    plugged -> { lastNet = net; stillSince = tick; dist = 0f; null }
                    speed != null && dist >= STEP_KM -> ((net - lastNet) / dist * 100f).also { dist = 0f; stillSince = tick }
                    // Sans vitesse (anciens firmwares) : un point par km du compteur
                    speed == null && start.km > lastKm && lastKm >= 0 -> {
                        val d = (start.km - lastKm).coerceAtMost(5)
                        ((net - lastNet) / d * 100f).also { stillSince = tick }
                    }
                    // À l'arrêt (voiture allumée, énergie qui sort) : un point par minute, compté comme 0,5 km
                    // (réglage : rien à l'arrêt, la courbe ne reflète alors que la conduite, #44)
                    (speed ?: 0f) < 1f && tick - stillSince >= 60_000 && net - lastNet > 0.001f && !sp.getBoolean("cc_stopped", true) -> {
                        lastNet = net; stillSince = tick; null
                    }
                    (speed ?: 0f) < 1f && tick - stillSince >= 60_000 && net - lastNet > 0.001f ->
                        ((net - lastNet) / STEP_KM * 100f).also { stillSince = tick; dist = 0f }
                    else -> null
                }
                if (start.km != lastKm && start.km >= 0) lastKm = start.km
                if (point != null) {
                    lastNet = net
                    if (point in 0f..80f) {
                        trip = (trip + point).takeLast(MAX_POINTS)
                        charge = (charge + point).takeLast(MAX_POINTS)
                        changed = true
                    }
                }
                if (changed) {
                    publish()
                    sp.edit().putString("cc_trip", join(trip)).putString("cc_charge", join(charge))
                        .putString("cc_trips", trips.joinToString(";") { "${it.first}:${String.format(java.util.Locale.US, "%.1f", it.second)}" })
                        .apply()
                }
            }
        }
    }
}
