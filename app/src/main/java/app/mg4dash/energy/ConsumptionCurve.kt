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
 *  - trajet en cours : un point par km (kWh nets × 100 / km), et à l'arrêt voiture allumée un
 *    point toutes les 2 minutes, compté comme 1 km ; vidée à chaque démarrage ;
 *  - depuis la recharge : les points de tous les trajets depuis la dernière recharge, datés ;
 *  - 12 derniers mois : un point par trajet (sa moyenne), à partir de 2 km.
 */
object ConsumptionCurve {
    private const val TAG = "ConsCurve"
    private const val MAX_POINTS = 1000
    /** Un point par km (#44), distance tirée de la vitesse ; à l'arrêt, deux minutes comptent pour autant. */
    private const val STEP_KM = 1f
    private const val STILL_MS = 120_000L
    private const val YEAR_MS = 365L * 24 * 3600 * 1000

    data class Curves(
        val trip: List<Float> = emptyList(),
        val charge: List<Float> = emptyList(),
        /** Moyenne de chaque trajet des 12 derniers mois (kWh/100), du plus ancien au plus récent. */
        val trips: List<Float> = emptyList(),
        /** Heure (ms) du plus ancien trajet de la courbe 12 mois, pour l'échelle de temps. */
        val firstTripAt: Long? = null,
        /** Heure (ms) de chaque point de [trips]. */
        val tripTimes: List<Long> = emptyList(),
        /** Heure (ms) de chaque point de [charge] ; vide tant que la courbe a des points d'avant les dates. */
        val chargeTimes: List<Long> = emptyList(),
    ) {
        /** Heures des points de la période, si elle en a. */
        fun timesOf(period: Int) = when (period) { 2 -> tripTimes; 0 -> chargeTimes; else -> emptyList() }
        /** Courbe de la période choisie : 0 = depuis la recharge, 1 = trajet, 2 = 12 mois. */
        fun of(period: Int) = when (period) { 1 -> trip; 2 -> trips; else -> charge }
    }

    /** Effacement demandé depuis l'écran : 0 = depuis la recharge, 1 = trajet, 2 = 12 mois ; -1 = rien. */
    @Volatile private var clearRequest = -1
    fun clear(period: Int) { clearRequest = period }

    private val _curves = MutableStateFlow(Curves())
    val curves: StateFlow<Curves> = _curves

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Courbe placée selon le temps : au plus ce nombre de tranches de durée égale. */
    private const val TIME_SLICES = 50

    /**
     * Points (position 0..1 sur la durée, valeur) : moyenne de chaque tranche de durée égale, à la date moyenne de ses
     * points, puis léger lissage avec les tranches voisines. Une tranche vide ne donne pas de point et coupe le lissage :
     * une période sans rouler reste visible, et aucune valeur ne déborde de l'autre côté.
     */
    fun timeLine(times: List<Long>, values: List<Float>, slices: Int = TIME_SLICES): List<Pair<Float, Float>> {
        if (times.size != values.size || times.size < 2) return emptyList()
        val from = times.minOrNull()!!; val span = times.maxOrNull()!! - from
        if (span <= 0L) return emptyList()
        val sumT = DoubleArray(slices); val sumV = DoubleArray(slices); val n = IntArray(slices)
        times.forEachIndexed { i, t ->
            val s = ((t - from).toDouble() / span * slices).toInt().coerceIn(0, slices - 1)
            sumT[s] += (t - from).toDouble(); sumV[s] += values[i].toDouble(); n[s]++
        }
        val mean = DoubleArray(slices) { if (n[it] > 0) sumV[it] / n[it] else 0.0 }
        return (0 until slices).filter { n[it] > 0 }.map { s ->
            val near = (s - 1..s + 1).filter { it in 0 until slices && n[it] > 0 }
            (sumT[s] / n[s] / span).toFloat() to near.map { mean[it] }.average().toFloat()
        }
    }

    private fun parse(s: String?) = (s ?: "").split(',').mapNotNull { it.toFloatOrNull() }
    private fun join(l: List<Float>) = l.joinToString(",") { String.format(java.util.Locale.US, "%.1f", it) }

    fun start(context: Context) {
        if (BuildConfig.MOCK) {
            val trip = List(36) { i -> 15.5f + 2.2f * kotlin.math.sin(i / 6f) + (if (i % 9 == 4) 1.2f else 0f) }
            val now = System.currentTimeMillis()
            val day = 24L * 3600 * 1000
            val chargePoints = 14 + trip.size
            // Exemple : aucun trajet pendant une semaine, à mi-parcours
            val tripDays = List(40) { i -> now - (23.0 * day * (39 - i) / 39).toLong() - if (i < 20) 7 * day else 0L }
            _curves.value = Curves(trip, List(14) { 16f + it % 4 * 0.6f } + trip, List(40) { i -> 16f + 3f * kotlin.math.sin(i / 7f) + (i % 5) * 0.4f },
                tripDays.first(), tripDays, List(chargePoints) { i -> now - (3.0 * day * (chargePoints - 1 - i) / (chargePoints - 1)).toLong() })
            return
        }
        val sp = context.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE)
        // Trajets de l'année : « horodatage:kWh/100 » séparés par « ; »
        val trips = (sp.getString("cc_trips", "") ?: "").split(';').mapNotNull { e ->
            val p = e.split(':'); if (p.size == 2) p[0].toLongOrNull()?.let { t -> p[1].toFloatOrNull()?.let { t to it } } else null
        }.toMutableList()
        // Trajet resté ouvert à l'arrêt précédent : la voiture coupe l'autoradio, MG4Dash ne voyait donc jamais
        // la fin du trajet et la courbe 12 mois restait vide (#44). Sa dernière moyenne est gardée ici.
        sp.getString("cc_open", null)?.split(':')?.let { p ->
            val t = p.getOrNull(0)?.toLongOrNull(); val avg = p.getOrNull(1)?.toFloatOrNull()
            if (t != null && avg != null) { trips.add(t to avg); Log.i(TAG, "previous trip kept for 12 months: ${"%.1f".format(java.util.Locale.US, avg)} kWh/100") }
            sp.edit().remove("cc_open").apply()
        }
        trips.removeAll { System.currentTimeMillis() - it.first > YEAR_MS }
        var trip = parse(sp.getString("cc_trip", "")).takeLast(MAX_POINTS)
        var charge = parse(sp.getString("cc_charge", "")).takeLast(MAX_POINTS)
        // Points d'avant les dates : sans heures, la courbe reste par km jusqu'à la prochaine recharge
        var chargeTimes = (sp.getString("cc_charge_t", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }.takeLast(MAX_POINTS)
        if (chargeTimes.size != charge.size) chargeTimes = emptyList()
        // 12 mois : les trajets finis, plus le trajet en cours dès 2 km (#44)
        fun publish() {
            val now = EnergyMonitor.sinceStart.value.per100
            val clock = System.currentTimeMillis()
            _curves.value = Curves(trip, charge, trips.map { it.second } + listOfNotNull(now),
                trips.firstOrNull()?.first ?: now?.let { clock },
                trips.map { it.first } + listOfNotNull(now?.let { clock }), chargeTimes)
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
                    sp.edit().remove("cc_open").apply()
                    seq = EnergyMonitor.tripSeq
                    trip = emptyList(); lastKm = start.km; lastNet = start.netKwh; stillSince = tick; dist = 0f
                    changed = true
                }
                // Recharge : les kWh depuis la recharge repartent de zéro
                val chargeOut = EnergyMonitor.sinceCharge.value.outKwh
                if (chargeOut + 0.05f < lastChargeOut) { charge = emptyList(); chargeTimes = emptyList(); changed = true }
                lastChargeOut = chargeOut
                lastStart = start

                // Effacement demandé (#44)
                when (clearRequest) {
                    0 -> { charge = emptyList(); chargeTimes = emptyList() }
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
                    // À l'arrêt (voiture allumée, énergie qui sort) : un point toutes les 2 minutes, compté comme 1 km
                    // (réglage : rien à l'arrêt, la courbe ne reflète alors que la conduite, #44)
                    (speed ?: 0f) < 1f && tick - stillSince >= STILL_MS && net - lastNet > 0.001f && !sp.getBoolean("cc_stopped", true) -> {
                        lastNet = net; stillSince = tick; null
                    }
                    (speed ?: 0f) < 1f && tick - stillSince >= STILL_MS && net - lastNet > 0.001f ->
                        ((net - lastNet) / STEP_KM * 100f).also { stillSince = tick; dist = 0f }
                    else -> null
                }
                if (start.km != lastKm && start.km >= 0) lastKm = start.km
                if (point != null) {
                    lastNet = net
                    if (point in 0f..80f) {
                        trip = (trip + point).takeLast(MAX_POINTS)
                        if (chargeTimes.size == charge.size) chargeTimes = (chargeTimes + System.currentTimeMillis()).takeLast(MAX_POINTS)
                        charge = (charge + point).takeLast(MAX_POINTS)
                        changed = true
                    }
                }
                if (changed) {
                    publish()
                    sp.edit().putString("cc_trip", join(trip)).putString("cc_charge", join(charge))
                        .putString("cc_charge_t", chargeTimes.joinToString(","))
                        .putString("cc_trips", trips.joinToString(";") { "${it.first}:${String.format(java.util.Locale.US, "%.1f", it.second)}" })
                        .apply {
                            // Moyenne du trajet en cours, reprise au prochain démarrage s'il se termine voiture coupée
                            start.per100?.let { putString("cc_open", "${System.currentTimeMillis()}:${String.format(java.util.Locale.US, "%.1f", it)}") }
                        }
                        .apply()
                }
            }
        }
    }
}
