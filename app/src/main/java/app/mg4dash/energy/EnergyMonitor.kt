package app.mg4dash.energy

import android.content.Context
import app.mg4dash.BuildConfig
import app.mg4dash.car.CarBridge
import app.mg4dash.car.CarProps
import app.mg4dash.car.VehicleState
import app.mg4dash.system.Log
import app.mg4dash.system.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Consommation mesurée, répartie entre conduite, climatisation et accessoires.
 *
 * Constaté sur la voiture (journal du 2026-10-14) : les compteurs de consommation SAIC restent
 * à zéro, mais tension et courant batterie sont fiables. Donc, chaque seconde :
 *  - P = tension × courant ; P > 0 = énergie consommée, P < 0 en roulant = récupération ;
 *  - à l'arrêt, clim coupée : P = accessoires (12 V, écran…) → appris, par état des feux /
 *    essuie-glaces (différence mesurée en conditions identiques) ;
 *  - à l'arrêt, clim allumée : P − accessoires = clim → appris par réglage (ventilation,
 *    chauffage / froid, dégivrage) ;
 *  - en roulant : clim et accessoires = valeurs apprises, le reste = conduite.
 * Trois périodes : depuis la dernière recharge, depuis le démarrage (pause de plus de 10 min), et les
 * 12 derniers mois (un total par mois, le plus ancien retiré : mémoire bornée, et l'évolution au fil
 * des saisons se lit mois par mois).
 */
object EnergyMonitor {
    private const val TAG = "EnergyMon"
    private const val SLEEP_GAP_MS = 10 * 60_000L
    /** Remontée du % (points) qui marque une recharge ; la récupération en descente reste en dessous. */
    const val RECHARGE_PCT = 3

    data class Totals(
        val outKwh: Float = 0f,      // énergie sortie de la batterie
        val regenKwh: Float = 0f,    // récupérée en roulant
        val climKwh: Float = 0f,
        val accKwh: Float = 0f,
        val km: Int = 0,
    ) {
        val driveKwh: Float get() = (outKwh - climKwh - accKwh).coerceAtLeast(0f)
        val netKwh: Float get() = (outKwh - regenKwh).coerceAtLeast(0f)
        /** kWh/100 km net (récupération déduite), à partir de 2 km. */
        val per100: Float? get() = if (km >= 2 && netKwh > 0.1f) (netKwh / km * 100f).takeIf { it in 3f..60f } else null
    }

    private val _sinceCharge = MutableStateFlow(Totals())
    val sinceCharge: StateFlow<Totals> = _sinceCharge
    private val _sinceStart = MutableStateFlow(Totals())
    val sinceStart: StateFlow<Totals> = _sinceStart
    /** % de batterie au départ du trajet en cours (repère de la barre d'autonomie, issue #29). */
    private val _tripStartSoc = MutableStateFlow<Int?>(null)
    val tripStartSoc: StateFlow<Int?> = _tripStartSoc
    /** Numéro du trajet, augmenté à chaque nouveau démarrage (remise à zéro des V min / A max, #29). */
    @Volatile var tripSeq = 0; private set
    private val _lastYear = MutableStateFlow(Totals())
    /** Les 12 derniers mois (mois en cours compris). */
    val lastYear: StateFlow<Totals> = _lastYear
    private val _months = MutableStateFlow<List<Pair<String, Totals>>>(emptyList())
    /** Totaux mois par mois (« 2026-10 »), du plus ancien au plus récent : 12 au plus. */
    val months: StateFlow<List<Pair<String, Totals>>> = _months
    private const val MONTHS = 12

    /** Période choisie à l'écran : 0 = depuis la recharge, 1 = depuis le démarrage, 2 = 12 derniers mois. */
    fun totals(period: Int): Totals = when (period) { 1 -> sinceStart.value; 2 -> lastYear.value; else -> sinceCharge.value }

    /** kWh/100 km de chaque mois assez roulé (au moins 20 km), pour la courbe de l'année. */
    fun monthlyPer100(): List<Float> = months.value.mapNotNull { (_, t) -> if (t.km >= 20) t.per100 else null }

    private fun monthKey(now: Long = System.currentTimeMillis()) =
        java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).format(java.util.Date(now))

    private fun sum(list: Collection<Totals>) = list.fold(Totals()) { a, t ->
        Totals(a.outKwh + t.outKwh, a.regenKwh + t.regenKwh, a.climKwh + t.climKwh, a.accKwh + t.accKwh, a.km + t.km)
    }

    /** Puissances apprises (W), pour l'affichage et le journal. */
    @Volatile var accessoriesW = 250f; private set
    @Volatile var climateW = 0f; private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start(context: Context) {
        val prefs = Prefs(context)
        val sp = prefs.raw
        if (BuildConfig.MOCK) {
            _sinceCharge.value = Totals(18.4f, 3.2f, 2.1f, 0.9f, 112)
            _sinceStart.value = Totals(3.6f, 0.7f, 0.5f, 0.2f, 21)
            val demo = listOf(19.8f, 19.1f, 17.6f, 16.0f, 15.1f, 14.4f, 14.2f, 14.6f, 15.3f, 16.4f, 17.9f, 19.2f)
            _months.value = demo.mapIndexed { i, v -> "2025-%02d".format(i + 1) to Totals(v * 10f, 1.5f, 1.2f, 0.4f, 1000).let { it.copy(outKwh = v * 10f + it.regenKwh) } }
            _lastYear.value = sum(_months.value.map { it.second })
            return
        }
        fun load(p: String) = Totals(sp.getFloat("${p}_out", 0f), sp.getFloat("${p}_rgn", 0f), sp.getFloat("${p}_clim", 0f), sp.getFloat("${p}_acc", 0f))
        _sinceCharge.value = load("em_c")
        accessoriesW = sp.getFloat("em_base", 250f)
        // Mois : « 2026-10=out,rgn,clim,acc,km » séparés par « ; » (12 au plus)
        val months = java.util.TreeMap<String, Totals>()
        sp.getString("em_months", "")!!.split(';').filter { it.contains('=') }.forEach { e ->
            val (k, v) = e.split('=', limit = 2)
            val f = v.split(',').mapNotNull { it.toFloatOrNull() }
            // Mois datés de 2019 (heure de l'autoradio pas encore remise au réveil, avant la 1.1.8) : écartés
            if (f.size == 5 && k >= "2024") months[k] = Totals(f[0], f[1], f[2], f[3], f[4].toInt())
        }
        fun publishMonths() {
            while (months.size > MONTHS) months.remove(months.firstKey())
            _months.value = months.entries.map { it.key to it.value }
            _lastYear.value = sum(months.values)
        }
        fun saveMonths() = sp.edit().putString("em_months",
            months.entries.joinToString(";") { (k, t) -> "$k=${t.outKwh},${t.regenKwh},${t.climKwh},${t.accKwh},${t.km}" }).apply()
        publishMonths()

        scope.launch {
            // Horloge interne : l'heure système de l'autoradio repart en 2019 à chaque réveil puis revient
            // (journal du 2026-10-19), ce qui donnait des durées négatives et faussait les kWh.
            var last = android.os.SystemClock.elapsedRealtime()
            var chargeKm = sp.getInt("em_c_km0", -1)
            var monthKm = -1   // compteur au dernier passage
            var pendingKm = 0  // km parcourus pas encore comptés dans le mois en cours
            var startKm = -1
            // Plus bas % de la période en cours (avant la 1.1.4 : dernier % vu, « em_last_soc »)
            var lowSoc = sp.getInt("em_c_low", sp.getInt("em_last_soc", -1))
            Log.i(TAG, "since charge: start ${sp.getInt("em_c_soc0", -1)} % at $chargeKm km, lowest $lowSoc %, " +
                "${"%.2f".format(java.util.Locale.US, _sinceCharge.value.netKwh)} kWh net")
            var saveTick = 0
            var sohSoc: Float? = null      // % à la seconde précédente, null hors observation
            var sohPrecise = false
            var sohKm: Int? = null         // compteur à la seconde précédente
            val learned = HashMap<String, Float>()
            fun get(k: String, def: Float) = learned.getOrPut(k) { sp.getFloat(k, def) }
            fun put(k: String, v: Float) { learned[k] = v }
            while (true) {
                delay(1000)
                val now = System.currentTimeMillis()
                val tick = android.os.SystemClock.elapsedRealtime()
                val dt = tick - last
                last = tick
                val s = CarBridge.state.value
                val km = s.odometerKm
                val soc = s.batteryPercent
                // Km du mois : lus à chaque passage (même quand la mesure de puissance est sautée)
                if (km != null) {
                    if (monthKm >= 0 && km > monthKm) pendingKm += (km - monthKm).coerceAtMost(1000)
                    monthKm = km
                }

                // Nouveau démarrage : longue pause (voiture en veille) ou premier passage
                if (dt > SLEEP_GAP_MS || startKm < 0) {
                    if (km != null) startKm = km
                    _sinceStart.value = Totals()
                    _tripStartSoc.value = soc
                    tripSeq++
                }
                if (_tripStartSoc.value == null && soc != null) {
                    _tripStartSoc.value = soc
                }
                // Recharge : le % remonte d'au moins RECHARGE_PCT points au-dessus du plus bas de la
                // période → nouvelle période « depuis la recharge ». Comparer au plus bas (et non au %
                // précédent) couvre aussi une recharge suivie % par % : MG4Dash peut tourner en veille
                // pendant la charge (constaté le 2026-10-18, compteurs non remis à zéro).
                if (soc != null) {
                    // Filet : % au-dessus du % de départ de la période → recharge forcément passée (période
                    // restée bloquée avant la 1.1.4, quand la recharge vue % par % n'était pas détectée)
                    val soc0 = sp.getInt("em_c_soc0", -1)
                    // Période incohérente : plus de 60 kWh/100 sur au moins 10 km (kWh d'une période
                    // précédente gardés avec le km de la nouvelle, voir l'enregistrement ci-dessous)
                    val c = _sinceCharge.value
                    val bogus = c.km >= 10 && c.netKwh / c.km * 100f > 60f
                    if (lowSoc >= 0 && soc >= lowSoc + RECHARGE_PCT || soc0 >= 0 && soc >= soc0 + RECHARGE_PCT ||
                        chargeKm < 0 && km != null || bogus) {
                        Log.i(TAG, if (bogus) "inconsistent since-charge period (${"%.1f".format(java.util.Locale.US, c.netKwh)} kWh net " +
                            "for ${c.km} km): reset" else "new since-charge period ($lowSoc → $soc %)")
                        _sinceCharge.value = Totals()
                        chargeKm = km ?: -1
                        lowSoc = soc
                        // Compteurs remis à zéro enregistrés tout de suite avec le km de départ : avant la 1.1.8,
                        // ils ne l'étaient qu'au prochain enregistrement (30 s, sauté voiture branchée), et
                        // MG4Dash arrêté entre-temps rechargeait les anciens kWh avec le nouveau km.
                        sp.edit().putInt("em_c_km0", chargeKm).putInt("em_c_soc0", soc).putInt("em_c_low", soc)
                            .putFloat("em_c_out", 0f).putFloat("em_c_rgn", 0f).putFloat("em_c_clim", 0f).putFloat("em_c_acc", 0f).apply()
                    } else if (lowSoc < 0 || soc < lowSoc) {
                        lowSoc = soc; sp.edit().putInt("em_c_low", soc).apply()
                    }
                }

                val v = CarProps.getFloat(CarProps.BMS_PACK_VOL)
                val i = CarProps.getFloat(CarProps.BMS_PACK_CRNT)
                if (v == null || i == null || v < 100f || v > 900f || dt <= 0 || dt > 5000 ||
                    VehicleState.isPlugged(s.chargeStatus)) {   // en charge ou V2L : hors conso
                    sohSoc = null; sohKm = null; continue
                }
                val p = v * i
                val h = dt / 3_600_000f
                // Santé batterie : énergie de cette seconde et baisse du % depuis la seconde précédente.
                // Le % au dixième si la voiture le donne (même source tout au long d'une séquence observée).
                val precise = s.socPrecise?.takeIf { soc != null && kotlin.math.abs(it - soc) < 1.5f }
                val cur = if (sohPrecise) precise else soc?.toFloat()
                val prev = sohSoc
                if (prev == null || cur == null || kotlin.math.abs(prev - cur) > 2f) {
                    sohPrecise = precise != null
                    sohSoc = precise ?: soc?.toFloat()
                    sohKm = km
                } else {
                    val prevKm = sohKm
                    val dKm = if (km != null && prevKm != null) (km - prevKm).coerceIn(0, 2) else 0
                    BatteryCapacity.accumulate(context, p * h / 1000f, prev - cur, dKm.toFloat())
                    sohSoc = cur
                    if (km != null) sohKm = km
                }
                // Anciens firmwares (SWI68, #19) : pas de vitesse véhicule, la récupération restait à 0 kWh ;
                // repli sur la vitesse GPS récente.
                val speed = s.speedKmh ?: app.mg4dash.map.LocationRepo.location.value
                    ?.takeIf { it.hasSpeed() && android.os.SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos < 10_000_000_000L }?.speed?.times(3.6f)
                val stopped = (speed ?: 0f) < 0.5f
                val hvacOn = s.hvacOn == true

                // Apprentissage à l'arrêt
                val accKey = "em_acc_" + accessoryKey(s)
                val accW = get(accKey, accessoriesW)
                val climKey = "em_clim_" + climateKey(s)
                if (stopped && p in 30f..8000f) {
                    if (!hvacOn) {
                        val nv = ema(accW, p, 0.02f)
                        put(accKey, nv)
                        if (accKey == "em_acc_off") { accessoriesW = nv; put("em_base", nv) }
                    } else {
                        val c = (p - accW).coerceAtLeast(0f)
                        put(climKey, ema(get(climKey, c), c, 0.05f))
                        put("em_clim_any", ema(get("em_clim_any", c), c, 0.05f))
                    }
                }

                // Répartition
                var out = 0f; var regen = 0f; var clim = 0f; var acc = 0f
                if (p >= 0f) {
                    out = p * h
                    val cw = if (!hvacOn) 0f else if (stopped) (p - accW).coerceAtLeast(0f)
                        else get(climKey, get("em_clim_any", 1000f))
                    climateW = cw
                    val cPart = minOf(cw, p)
                    val aPart = minOf(accW, p - cPart)
                    clim = cPart * h; acc = aPart * h
                } else if (!stopped) {
                    regen = -p * h
                }
                fun add(t: Totals, k: Int?, k0: Int) = t.copy(
                    outKwh = t.outKwh + out / 1000f, regenKwh = t.regenKwh + regen / 1000f,
                    climKwh = t.climKwh + clim / 1000f, accKwh = t.accKwh + acc / 1000f,
                    km = if (k != null && k0 >= 0) (k - k0).coerceAtLeast(0) else t.km,
                )
                _sinceCharge.value = add(_sinceCharge.value, km, chargeKm)
                _sinceStart.value = add(_sinceStart.value, km, startKm)
                val mk = monthKey(now)
                val m = months[mk] ?: Totals()
                // Heure système pas encore remise à l'heure (2019 au réveil) : mois inconnu, km gardés pour après
                if (mk >= "2024") {
                    val dKm = pendingKm; pendingKm = 0
                    months[mk] = m.copy(
                        outKwh = m.outKwh + out / 1000f, regenKwh = m.regenKwh + regen / 1000f,
                        climKwh = m.climKwh + clim / 1000f, accKwh = m.accKwh + acc / 1000f, km = m.km + dKm,
                    )
                    publishMonths()
                }

                if (++saveTick >= 30) {
                    saveTick = 0
                    val c = _sinceCharge.value
                    val e = sp.edit()
                    learned.forEach { (k, v) -> e.putFloat(k, v) }
                    e.putFloat("em_c_out", c.outKwh).putFloat("em_c_rgn", c.regenKwh)
                        .putFloat("em_c_clim", c.climKwh).putFloat("em_c_acc", c.accKwh)
                    e.apply()
                    saveMonths()
                    BatteryCapacity.flush(context)
                }
            }
        }
    }

    /** Réglage de clim : ventilation (tranches), chauffage / froid / ventilation seule, dégivrage. */
    private fun climateKey(s: VehicleState): String {
        val fan = s.fanSpeed ?: 0
        val fanBucket = when { fan >= 15 -> "max"; fan >= 7 -> "hi"; fan >= 4 -> "mid"; else -> "lo" }
        val set = s.driverTemp; val outside = s.outsideTemp
        val mode = when {
            set == null || outside == null -> "x"
            set > outside + 2 -> "heat"
            set < outside - 2 -> "cool"
            else -> "vent"
        }
        val def = (if (s.defrostFront == true) "F" else "") + (if (s.defrostRear == true) "R" else "")
        return "$mode-$fanBucket-$def"
    }

    /** Équipements électriques visibles : feux, essuie-glaces. "off" = rien d'allumé. */
    private fun accessoryKey(s: VehicleState): String {
        val parts = buildList {
            // Feux : VEH_SIDE_LGHT (0 = éteints), sinon mode jour / nuit du système (3 = feux allumés),
            // relevés sur la voiture le 2026-10-14 ; HEADLIGHTS_STATE n'existe pas sur ce modèle.
            val side = CarProps.readAny(557880099)
            val night = CarProps.readAny(289442317)
            if (side.isNotEmpty() && side != "0" && side != "false" || side.isEmpty() && night == "3") add("lights")
            if (CarProps.readAny(557897511).let { it.isNotEmpty() && it != "0" }) add("wiper")
            if (s.defrostRear == true && s.hvacOn != true) add("rear")
        }
        return if (parts.isEmpty()) "off" else parts.joinToString("+")
    }

    private fun ema(old: Float, new: Float, k: Float) = old + (new - old) * k
}
