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

    /** Composition d'un point (kWh/100), récupération déduite de la conduite. */
    data class Mix(val drive: Float, val clim: Float, val heat: Float, val acc: Float) {
        fun parts() = listOf(drive, clim, heat, acc)
    }

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
        /** Composition de chaque point ; null pour les points d'avant son enregistrement. */
        val tripMix: List<Mix?> = emptyList(),
        val chargeMix: List<Mix?> = emptyList(),
        val tripsMix: List<Mix?> = emptyList(),
    ) {
        fun mixOf(period: Int) = when (period) { 1 -> tripMix; 2 -> tripsMix; else -> chargeMix }
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
     * points, puis lissage avec les tranches voisines. Une tranche vide ne donne pas de point et coupe le lissage :
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
            // Jusqu'à 2 tranches de chaque côté, sans franchir une tranche vide
            val before = (s - 1 downTo maxOf(0, s - 2)).takeWhile { n[it] > 0 }
            val after = (s + 1..minOf(slices - 1, s + 2)).takeWhile { n[it] > 0 }
            val near = before + s + after
            (sumT[s] / n[s] / span).toFloat() to near.map { mean[it] }.average().toFloat()
        }
    }

    /** Composition moyenne d'un ensemble d'énergies sur [km] km, à l'échelle de [total] si elle est donnée. */
    private fun mix(drive: Float, clim: Float, heat: Float, acc: Float, regen: Float, km: Float, total: Float? = null): Mix? {
        if (km <= 0f) return null
        val parts = listOf((drive - regen).coerceAtLeast(0f), clim.coerceAtLeast(0f), heat.coerceAtLeast(0f), acc.coerceAtLeast(0f)).map { it / km * 100f }
        val sum = parts.sum()
        if (sum <= 0f) return null
        val k = if (total != null) total / sum else 1f
        return Mix(parts[0] * k, parts[1] * k, parts[2] * k, parts[3] * k)
    }

    private fun mixOfTotals(t: EnergyMonitor.Totals) =
        if (t.km < 2) null else mix(t.driveKwh, t.climKwh, t.heatKwh, t.accKwh, t.regenKwh, t.km.toFloat())

    private fun mixText(m: Mix?) = m?.parts()?.joinToString(":") { String.format(java.util.Locale.US, "%.1f", it) } ?: ""
    private fun parseMix(s: String?): Mix? {
        val p = s?.split(':')?.mapNotNull { it.toFloatOrNull() } ?: return null
        return if (p.size == 4) Mix(p[0], p[1], p[2], p[3]) else null
    }
    /** Compositions enregistrées, une par point ; aucune si elles ne correspondent pas aux points (anciennes courbes). */
    private fun parseMixes(s: String?, size: Int): List<Mix?> {
        val l = (s ?: "").split(';').map { parseMix(it) }
        return if (l.size == size) l else List(size) { null }
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
            val mock = { i: Int, v: Float -> Mix(v * (0.72f - i % 9 * 0.01f), v * (0.16f + i % 9 * 0.01f), v * 0.05f, v * 0.07f) }
            val charge = List(14) { 16f + it % 4 * 0.6f } + trip
            val year = List(40) { i -> 16f + 3f * kotlin.math.sin(i / 7f) + (i % 5) * 0.4f }
            _curves.value = Curves(trip, charge, year,
                tripDays.first(), tripDays, List(chargePoints) { i -> now - (3.0 * day * (chargePoints - 1 - i) / (chargePoints - 1)).toLong() },
                trip.mapIndexed(mock), charge.mapIndexed(mock), year.mapIndexed(mock))
            return
        }
        val sp = context.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE)
        // Trajets de l'année : « horodatage:kWh/100 » séparés par « ; »
        val trips = (sp.getString("cc_trips", "") ?: "").split(';').mapNotNull { e ->
            val p = e.split(':'); if (p.size == 2) p[0].toLongOrNull()?.let { t -> p[1].toFloatOrNull()?.let { t to it } } else null
        }.toMutableList()
        // Composition de ces trajets, par heure : « horodatage=conduite:clim:batterie:accessoires »
        val tripMixes = (sp.getString("cc_trips_m", "") ?: "").split(';').mapNotNull { e ->
            val p = e.split('='); p.getOrNull(0)?.toLongOrNull()?.let { t -> parseMix(p.getOrNull(1))?.let { t to it } }
        }.toMap().toMutableMap()
        // Trajet resté ouvert à l'arrêt précédent : la voiture coupe l'autoradio, MG4Dash ne voyait donc jamais
        // la fin du trajet et la courbe 12 mois restait vide (#44). Sa dernière moyenne est gardée ici.
        sp.getString("cc_open", null)?.split(':')?.let { p ->
            val t = p.getOrNull(0)?.toLongOrNull(); val avg = p.getOrNull(1)?.toFloatOrNull()
            if (t != null && p.size == 6) parseMix(p.drop(2).joinToString(":"))?.let { tripMixes[t] = it }
            if (t != null && avg != null) { trips.add(t to avg); Log.i(TAG, "previous trip kept for 12 months: ${"%.1f".format(java.util.Locale.US, avg)} kWh/100") }
            sp.edit().remove("cc_open").apply()
        }
        trips.removeAll { System.currentTimeMillis() - it.first > YEAR_MS }
        var trip = parse(sp.getString("cc_trip", "")).takeLast(MAX_POINTS)
        var charge = parse(sp.getString("cc_charge", "")).takeLast(MAX_POINTS)
        // Points d'avant les dates : sans heures, la courbe reste par km jusqu'à la prochaine recharge
        var chargeTimes = (sp.getString("cc_charge_t", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }.takeLast(MAX_POINTS)
        if (chargeTimes.size != charge.size) chargeTimes = emptyList()
        var tripMix = parseMixes(sp.getString("cc_trip_m", ""), trip.size)
        var chargeMix = parseMixes(sp.getString("cc_charge_m", ""), charge.size)
        // 12 mois : les trajets finis, plus le trajet en cours dès 2 km (#44)
        fun publish() {
            val now = EnergyMonitor.sinceStart.value.per100
            val clock = System.currentTimeMillis()
            _curves.value = Curves(trip, charge, trips.map { it.second } + listOfNotNull(now),
                trips.firstOrNull()?.first ?: now?.let { clock },
                trips.map { it.first } + listOfNotNull(now?.let { clock }), chargeTimes,
                tripMix, chargeMix, trips.map { tripMixes[it.first] } + (if (now != null) listOf(mixOfTotals(EnergyMonitor.sinceStart.value)) else emptyList()))
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
            var mixBase = EnergyMonitor.sinceStart.value   // énergies au dernier point : la composition du suivant en découle
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
                        mixOfTotals(lastStart)?.let { tripMixes[now] = it }
                        trips.removeAll { now - it.first > YEAR_MS }
                        tripMixes.keys.retainAll(trips.map { it.first }.toSet())
                        Log.i(TAG, "trip ended: ${lastStart.km} km, ${"%.1f".format(java.util.Locale.US, avg)} kWh/100")
                    }
                    sp.edit().remove("cc_open").apply()
                    seq = EnergyMonitor.tripSeq
                    trip = emptyList(); tripMix = emptyList(); mixBase = start
                    lastKm = start.km; lastNet = start.netKwh; stillSince = tick; dist = 0f
                    changed = true
                }
                // Recharge : les kWh depuis la recharge repartent de zéro
                val chargeOut = EnergyMonitor.sinceCharge.value.outKwh
                if (chargeOut + 0.05f < lastChargeOut) { charge = emptyList(); chargeTimes = emptyList(); chargeMix = emptyList(); changed = true }
                lastChargeOut = chargeOut
                lastStart = start

                // Effacement demandé (#44)
                when (clearRequest) {
                    0 -> { charge = emptyList(); chargeTimes = emptyList(); chargeMix = emptyList() }
                    1 -> { trip = emptyList(); tripMix = emptyList() }
                    2 -> { trips.clear(); tripMixes.clear() }
                }
                if (clearRequest >= 0) { clearRequest = -1; changed = true }
                val net = start.netKwh
                val plugged = app.mg4dash.car.VehicleState.isPlugged(CarBridge.state.value.chargeStatus)
                val speed = CarBridge.state.value.speedKmh
                val dt = (tick - lastTick).coerceIn(0L, 5000L)
                lastTick = tick
                if (speed != null && speed > 0f) dist += speed * dt / 3_600_000f
                val point: Float? = when {
                    plugged -> { lastNet = net; mixBase = start; stillSince = tick; dist = 0f; null }
                    speed != null && dist >= STEP_KM -> ((net - lastNet) / dist * 100f).also { dist = 0f; stillSince = tick }
                    // Sans vitesse (anciens firmwares) : un point par km du compteur
                    speed == null && start.km > lastKm && lastKm >= 0 -> {
                        val d = (start.km - lastKm).coerceAtMost(5)
                        ((net - lastNet) / d * 100f).also { stillSince = tick }
                    }
                    // À l'arrêt (voiture allumée, énergie qui sort) : un point toutes les 2 minutes, compté comme 1 km
                    // (réglage : rien à l'arrêt, la courbe ne reflète alors que la conduite, #44)
                    (speed ?: 0f) < 1f && tick - stillSince >= STILL_MS && net - lastNet > 0.001f && !sp.getBoolean("cc_stopped", true) -> {
                        lastNet = net; mixBase = start; stillSince = tick; null
                    }
                    (speed ?: 0f) < 1f && tick - stillSince >= STILL_MS && net - lastNet > 0.001f ->
                        ((net - lastNet) / STEP_KM * 100f).also { stillSince = tick; dist = 0f }
                    else -> null
                }
                if (start.km != lastKm && start.km >= 0) lastKm = start.km
                if (point != null) {
                    lastNet = net
                    // Composition du point : ce qui a consommé depuis le précédent, à l'échelle du point
                    val m = mix(start.driveKwh - mixBase.driveKwh, start.climKwh - mixBase.climKwh, start.heatKwh - mixBase.heatKwh,
                        start.accKwh - mixBase.accKwh, start.regenKwh - mixBase.regenKwh, 1f, point)
                    mixBase = start
                    if (point in 0f..80f) {
                        tripMix = (tripMix + m).takeLast(MAX_POINTS)
                        chargeMix = (chargeMix + m).takeLast(MAX_POINTS)
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
                        .putString("cc_trip_m", tripMix.joinToString(";") { mixText(it) })
                        .putString("cc_charge_m", chargeMix.joinToString(";") { mixText(it) })
                        .putString("cc_trips_m", tripMixes.entries.joinToString(";") { "${it.key}=${mixText(it.value)}" })
                        .putString("cc_trips", trips.joinToString(";") { "${it.first}:${String.format(java.util.Locale.US, "%.1f", it.second)}" })
                        .apply {
                            // Moyenne du trajet en cours, reprise au prochain démarrage s'il se termine voiture coupée
                            start.per100?.let { putString("cc_open", "${System.currentTimeMillis()}:${String.format(java.util.Locale.US, "%.1f", it)}" +
                                (mixOfTotals(start)?.let { x -> ":" + mixText(x) } ?: "")) }
                        }
                        .apply()
                }
            }
        }
    }
}
