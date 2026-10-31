package app.mg4dash.energy

import android.content.Context
import app.mg4dash.system.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Carte de consommation (en test, désactivée par défaut) : consommation de roulage seule (sans clim ni
 * accessoires, récupération déduite) par vitesse moyenne et par température extérieure, affinée trajet
 * après trajet. Elle servira à estimer un itinéraire (DiAuto) : plan dans PLAN_CARTE_CONSO.md.
 *
 * Tronçons d'environ 1 km ; dénivelé corrigé par l'altitude GPS ; écartés : arrêt long, recharge,
 * réchauffage de la batterie pas encore appris, vitesse trop variable, valeur aberrante. Table : 15 cases de vitesse (10 km/h) × 14 cases
 * de température (5 °C, de -20 à 45 °C), chacune avec ses km, ses kWh et sa dispersion (quelques Ko).
 */
object ConsumptionMap {
    private const val TAG = "ConsMap"
    private const val FILE = "consumption_map.json"
    const val SPEED_STEP = 10
    const val SPEED_BINS = 15             // 0-10 … 140-150 km/h et plus
    const val TEMP_MIN = -20
    const val TEMP_STEP = 5
    const val TEMP_BINS = 14              // -20…-15 … 45 °C et plus
    private const val SEGMENT_KM = 1f
    private const val STOP_MS = 60_000L   // arrêt plus long : tronçon écarté (bouchon, feu, parking)
    private const val MASS_KG = 1800f     // MG4 en ordre de marche, conducteur compris
    private const val UP_EFF = 0.9f       // montée : énergie électrique ≈ énergie potentielle / rendement
    private const val DOWN_EFF = 0.7f     // descente : part de l'énergie potentielle récupérée
    private const val STABLE_KMH = 10f    // vitesse « stable » : à ±10 km/h de la moyenne du tronçon…
    private const val STABLE_SHARE = 0.8f // … au moins 80 % du temps, sinon tronçon écarté (vitesses mélangées)

    /** Une case : km roulés, kWh (corrigés du dénivelé), nombre de tronçons, somme pondérée des carrés (dispersion). */
    data class Cell(var km: Float = 0f, var kwh: Float = 0f, var n: Int = 0, var sq: Float = 0f) {
        val per100: Float? get() = if (km > 0f) kwh / km * 100f else null
        /** Écart-type de la conso des tronçons (kWh/100 km), pondéré par la distance. */
        val spread: Float? get() = per100?.let { m -> kotlin.math.sqrt((sq / km - m * m).coerceAtLeast(0f)) }
    }

    @Volatile private var cells: Array<Array<Cell>>? = null

    fun enabled(context: Context) = app.mg4dash.system.Prefs(context).consumptionMap

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun table(context: Context): Array<Array<Cell>> = cells ?: load(context).also { cells = it }

    private fun empty() = Array(SPEED_BINS) { Array(TEMP_BINS) { Cell() } }

    private fun load(context: Context): Array<Array<Cell>> = try {
        fromJson(file(context).takeIf { it.exists() }?.readText())
    } catch (e: Exception) { Log.w(TAG, "load: ${e.message}"); empty() }

    fun fromJson(text: String?): Array<Array<Cell>> {
        val t = empty()
        if (text.isNullOrBlank()) return t
        val a = JSONObject(text).getJSONArray("cells")
        for (i in 0 until a.length()) {
            val c = a.getJSONArray(i)
            val s = c.getInt(0); val tb = c.getInt(1)
            if (s in 0 until SPEED_BINS && tb in 0 until TEMP_BINS)
                t[s][tb] = Cell(c.getDouble(2).toFloat(), c.getDouble(3).toFloat(), c.getInt(4), c.getDouble(5).toFloat())
        }
        return t
    }

    /** Format du fichier (aussi celui de l'export) : cases non vides [vitesse, température, km, kWh, n, carrés]. */
    fun toJson(t: Array<Array<Cell>>): String {
        val a = JSONArray()
        for (s in 0 until SPEED_BINS) for (tb in 0 until TEMP_BINS) t[s][tb].takeIf { it.n > 0 }?.let { c ->
            a.put(JSONArray().put(s).put(tb).put(c.km.toDouble()).put(c.kwh.toDouble()).put(c.n).put(c.sq.toDouble()))
        }
        return JSONObject().put("version", 1).put("speedStep", SPEED_STEP).put("tempMin", TEMP_MIN).put("tempStep", TEMP_STEP).put("cells", a).toString()
    }

    private fun save(context: Context) {
        val t = cells ?: return
        try { file(context).writeText(toJson(t)) } catch (e: Exception) { Log.w(TAG, "save: ${e.message}") }
        // DiAuto relit la carte (ConsumptionProvider)
        try { context.contentResolver.notifyChange(ConsumptionProvider.MAP_URI, null) } catch (_: Exception) {}
    }

    /** Heure (ms) de la dernière modification de la carte, 0 si elle n'a jamais été enregistrée. */
    fun updatedAt(context: Context): Long = file(context).lastModified()

    /** Remplace la carte (import depuis la clé USB). */
    fun replace(context: Context, t: Array<Array<Cell>>) { cells = t; save(context); Log.i(TAG, "map imported") }

    fun clear(context: Context) { cells = empty(); save(context); Log.i(TAG, "map cleared") }

    fun speedBin(kmh: Float) = (kmh / SPEED_STEP).toInt().coerceIn(0, SPEED_BINS - 1)
    fun tempBin(c: Float) = ((c - TEMP_MIN) / TEMP_STEP).toInt().coerceIn(0, TEMP_BINS - 1)

    // ── Tronçon en cours ──
    private var segKwh = 0f
    private var segKm = 0f
    private var segMs = 0L
    private var segTempSum = 0f
    private var segTempN = 0
    private var segAlt0: Double? = null
    private val segSpeeds = ArrayList<Float>()   // vitesse et durée de chaque seconde du tronçon
    private val segDts = ArrayList<Long>()
    private var stillMs = 0L
    private var spoiled = false

    private fun reset(alt: Double?) {
        segKwh = 0f; segKm = 0f; segMs = 0L; segTempSum = 0f; segTempN = 0; segAlt0 = alt; stillMs = 0L; spoiled = false
        segSpeeds.clear(); segDts.clear()
    }

    /** Fin du trajet (voiture en veille, recharge) : le tronçon en cours est abandonné. */
    fun interrupt() { reset(null) }

    /**
     * Une seconde de roulage, depuis EnergyMonitor : énergie de conduite nette (kWh, clim et accessoires
     * déduits, récupération déduite), durée, vitesse (km/h), température extérieure (°C).
     */
    fun tick(context: Context, driveKwh: Float, dtMs: Long, speedKmh: Float?, outside: Float?) {
        if (!enabled(context)) return
        // Réchauffage de la batterie : déduit par EnergyMonitor (puissance apprise) ; tant qu'elle ne l'est
        // pas, les tronçons où il peut chauffer sont écartés
        val ptcOn = EnergyMonitor.batteryHeatPossible && !EnergyMonitor.batteryHeatKnown
        val loc = app.mg4dash.map.LocationRepo.location.value
        val alt = loc?.takeIf { it.hasAltitude() }?.altitude
        if (segMs == 0L && segKm == 0f) segAlt0 = alt
        if (ptcOn || speedKmh == null || outside == null) spoiled = true
        val v = speedKmh ?: 0f
        segKwh += driveKwh
        segMs += dtMs
        segKm += v * dtMs / 3_600_000f
        segSpeeds.add(v); segDts.add(dtMs)
        if (outside != null) { segTempSum += outside; segTempN++ }
        stillMs = if (v < 1f) stillMs + dtMs else 0L
        if (stillMs > STOP_MS) spoiled = true
        if (segKm < SEGMENT_KM) return
        // Tronçon complet
        val km = segKm; val hours = segMs / 3_600_000f
        var kwh = segKwh
        val a0 = segAlt0
        if (a0 != null && alt != null) {
            val dh = (alt - a0).toFloat()
            val potential = MASS_KG * 9.81f * dh / 3_600_000f   // kWh
            kwh -= if (dh > 0) potential / UP_EFF else potential * DOWN_EFF
        }
        val per100 = kwh / km * 100f
        // Vitesse stable : un km fait à 30 puis à 90 ne doit pas compter comme un km à 45
        val mean = if (hours > 0f) km / hours else 0f
        var stableMs = 0L
        for (i in segSpeeds.indices) if (kotlin.math.abs(segSpeeds[i] - mean) <= STABLE_KMH) stableMs += segDts[i]
        val stable = segMs > 0 && stableMs >= STABLE_SHARE * segMs
        val ok = !spoiled && stable && hours > 0f && segTempN > 0 && per100 in 2f..60f
        if (!stable && !spoiled && app.mg4dash.system.Prefs(context).energyLog)
            Log.i(TAG, String.format(java.util.Locale.US, "segment at %.0f km/h skipped: speed stable %.0f %% of the time", mean, stableMs * 100f / segMs.coerceAtLeast(1)))
        if (ok) {
            val speed = km / hours
            val temp = segTempSum / segTempN
            val c = table(context)[speedBin(speed)][tempBin(temp)]
            c.km += km; c.kwh += kwh; c.n++; c.sq += per100 * per100 * km
            save(context)
            if (app.mg4dash.system.Prefs(context).energyLog)
                Log.i(TAG, String.format(java.util.Locale.US, "segment %.2f km at %.0f km/h, %.1f °C: %.1f kWh/100 (cell %d now %.1f, %.0f km)",
                    km, speed, temp, per100, c.n, c.per100 ?: 0f, c.km))
        }
        reset(alt)
    }
}
