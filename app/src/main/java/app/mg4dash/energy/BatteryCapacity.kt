package app.mg4dash.energy

import android.content.Context
import app.mg4dash.car.CarProps
import app.mg4dash.system.Log
import app.mg4dash.system.Prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Capacité utile de la batterie et santé (SOH) estimée, sans réglage à la main si possible.
 *
 * Capacité :
 *  1) la valeur annoncée par la voiture (INFO_EV_BATTERY_CAPACITY, AOSP, en Wh ; MG4Control l'a
 *     trouvée à 0 sur SWI133, elle peut répondre ailleurs) ;
 *  2) sinon, la mesure de MG4Dash, faite en roulant (décharge) : énergie nette sortie de la batterie
 *     (récupération déduite) ÷ baisse du %, comptées seulement quand MG4Dash voit les deux (écran
 *     allumé). Le % perdu voiture fermée, et la recharge, sont hors mesure : la baisse se cumule sur
 *     plusieurs trajets et plusieurs jours, sans attendre de recharge, et le cumul est gardé avec les
 *     préférences (conservées par les mises à jour). Dès 15 % de baisse cumulée, la mesure rejoint
 *     l'historique, datée ; lissage : moyenne pondérée par la baisse de %, sur les 30 derniers jours.
 * Santé : capacité mesurée ÷ capacité utile d'origine du modèle (51, 64 ou 77 kWh), aussi lissée
 * sur 30 jours, et sur toute la période mesurée pour suivre l'évolution.
 *
 * Jusqu'à la 1.1.3, la mesure allait d'une recharge à la suivante : elle comptait le % perdu voiture
 * fermée sans l'énergie correspondante, et ne se terminait pas si la recharge était vue % par %.
 */
object BatteryCapacity {
    private const val TAG = "Capacity"
    private const val PROP_EV_BATTERY_CAPACITY = 0x11600106
    const val MIN_DROP = 15f
    private const val MONTH_MS = 30L * 24 * 3600_000L
    private const val MAX_SAMPLES = 300

    /** Modèles : capacité brute (affichée) → capacité utile d'origine (kWh). */
    val MODELS = listOf(51 to 50.8f, 64 to 61.7f, 77 to 74.4f)

    /** Une mesure : date, capacité (kWh), baisse du % et km parcourus pendant la mesure (0 avant la 1.1.5). */
    data class Sample(val at: Long, val kwh: Float, val drop: Int, val km: Float = 0f)

    /** Capacité annoncée par la voiture, en kWh, ou null. */
    fun fromCar(): Float? {
        val raw = CarProps.readFloatArea(PROP_EV_BATTERY_CAPACITY, 0)?.takeIf { it.isFinite() && it > 0f }
            ?: CarProps.readFloatArea(PROP_EV_BATTERY_CAPACITY, 0x1000000)?.takeIf { it.isFinite() && it > 0f }
            ?: return null
        val kwh = if (raw > 1000f) raw / 1000f else raw
        return kwh.takeIf { it in 20f..120f }
    }

    fun samples(context: Context): List<Sample> = try {
        val arr = JSONArray(Prefs(context).raw.getString("cap_samples", "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Sample(o.getLong("t"), o.getDouble("k").toFloat(), o.getInt("d"), o.optDouble("m", 0.0).toFloat()) } }
    } catch (_: Exception) { emptyList() }

    private fun save(context: Context, list: List<Sample>) {
        val arr = JSONArray()
        list.takeLast(MAX_SAMPLES).forEach { arr.put(JSONObject().put("t", it.at).put("k", it.kwh.toDouble()).put("d", it.drop).put("m", it.km.toDouble())) }
        Prefs(context).raw.edit().putString("cap_samples", arr.toString()).apply()
    }

    private fun weighted(list: List<Sample>): Float? {
        val w = list.sumOf { it.drop }
        return if (w == 0) null else list.sumOf { (it.kwh * it.drop).toDouble() }.toFloat() / w
    }

    /** Capacité mesurée, lissée sur 30 jours (sinon toutes les mesures), et nombre de mesures. */
    fun estimate(context: Context): Pair<Float, Int>? {
        val all = samples(context)
        val recent = all.filter { System.currentTimeMillis() - it.at < MONTH_MS }
        weighted(recent)?.let { return it to recent.size }
        weighted(all)?.let { return it to all.size }
        return null
    }

    // Mesure en cours : énergie nette (kWh) et baisse du % cumulées, gardées en mémoire et
    // enregistrées par [flush] (toutes les 30 s par EnergyMonitor).
    private var accKwh = 0f
    private var accDrop = 0f
    private var accKm = 0f
    private var loaded = false

    private fun load(context: Context) {
        if (loaded) return
        val sp = Prefs(context).raw
        accKwh = sp.getFloat("soh_kwh", 0f); accDrop = sp.getFloat("soh_drop", 0f); accKm = sp.getFloat("soh_km", 0f)
        // Anciennes clés (mesure d'une recharge à l'autre) : plus utilisées
        if (sp.contains("cap_current")) sp.edit().remove("cap_current").remove("cap_current_drop").apply()
        loaded = true
    }

    @Synchronized
    fun flush(context: Context) {
        if (!loaded) return
        Prefs(context).raw.edit().putFloat("soh_kwh", accKwh).putFloat("soh_drop", accDrop).putFloat("soh_km", accKm).apply()
    }

    /**
     * Une seconde observée : [kwh] = énergie sortie de la batterie (négative en récupération),
     * [drop] = baisse du % sur la même seconde (négative si le % remonte), [km] = distance parcourue.
     */
    @Synchronized
    fun accumulate(context: Context, kwh: Float, drop: Float, km: Float) {
        load(context)
        accKwh += kwh; accDrop += drop; accKm += km
        if (accDrop < MIN_DROP) return
        val est = valid(accKwh, accDrop)
        if (est != null) {
            save(context, samples(context) + Sample(System.currentTimeMillis(), est, Math.round(accDrop), accKm))
            Log.i(TAG, "sample ${"%.1f".format(est)} kWh over ${"%.1f".format(accDrop)} % added")
        } else {
            Log.i(TAG, "sample rejected: ${"%.2f".format(accKwh)} kWh for ${"%.1f".format(accDrop)} %")
        }
        accKwh = 0f; accDrop = 0f; accKm = 0f
        flush(context)
        if (est != null) apply(context)
    }

    /** Mesure en cours : baisse cumulée (%), capacité provisoire (kWh, à partir de 5 %) et km parcourus. */
    data class Progress(val drop: Float, val provisional: Float?, val km: Float)

    @Synchronized
    fun progress(context: Context): Progress {
        load(context)
        val prov = if (accDrop >= 5f && accKwh > 0f) (accKwh / accDrop * 100f).takeIf { it in 30f..100f } else null
        return Progress(accDrop.coerceAtLeast(0f), prov, accKm.coerceAtLeast(0f))
    }

    private fun valid(netKwh: Float, socDrop: Float): Float? {
        if (socDrop < MIN_DROP || netKwh < 3f) return null
        return (netKwh / socDrop * 100f).takeIf { it in 30f..100f }
    }

    /** Capacité utile d'origine du modèle choisi (kWh). */
    fun nominal(context: Context): Float {
        val gross = Prefs(context).batteryModel
        return MODELS.firstOrNull { it.first == gross }?.second ?: 50.8f
    }

    /** Santé (%, au dixième) sur les mesures des 30 derniers jours (sinon toutes), et km couverts par ces mesures. */
    data class Health(val percent: Float, val km: Float, val count: Int)

    fun health(context: Context): Health? {
        val all = samples(context).takeIf { it.isNotEmpty() } ?: return null
        val recent = all.filter { System.currentTimeMillis() - it.at < MONTH_MS }.ifEmpty { all }
        val m = weighted(recent) ?: return null
        return Health((m / nominal(context) * 100f).coerceAtMost(100f), recent.sumOf { it.km.toDouble() }.toFloat(), recent.size)
    }

    /** Remet la capacité utilisée sur la meilleure source, sauf réglage manuel. */
    fun apply(context: Context) {
        val prefs = Prefs(context)
        if (prefs.batteryKwhManual) return
        val v = fromCar() ?: estimate(context)?.first ?: return
        if (Math.abs(v - prefs.batteryKwh) >= 0.1f) {
            prefs.batteryKwh = Math.round(v * 10f) / 10f
            Log.i(TAG, "capacity used: ${prefs.batteryKwh} kWh")
        }
    }
}
