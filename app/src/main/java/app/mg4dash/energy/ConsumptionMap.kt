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
 * Échantillons : au moins 15 s dans la même tranche de 10 km/h (1 km au plus), corrigés de l'élan (vitesse de
 * la voiture) et du dénivelé (altitude GPS lissée) ; écartés : arrêt long, recharge, réchauffage de la batterie
 * pas encore appris, valeur aberrante. Table : 15 cases de vitesse (10 km/h) × 14 cases
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
    private const val SEGMENT_KM = 1f     // échantillon fermé au plus tard après 1 km…
    private const val SAMPLE_MS = 15_000L // … gardé s'il a duré au moins 15 s dans la même tranche de vitesse
    private const val MARGIN_KMH = 3f     // marge autour de la tranche avant de la considérer quittée
    private const val ALT_SMOOTH_MS = 30_000.0
    private const val STOP_MS = 60_000L   // arrêt plus long : échantillon écarté (bouchon, feu, parking)
    private const val MASS_KG = 1800f     // MG4 en ordre de marche, conducteur compris
    private const val UP_EFF = 0.9f       // montée, accélération : énergie électrique ≈ énergie gagnée / rendement
    private const val DOWN_EFF = 0.7f     // descente, freinage : part de l'énergie rendue récupérée

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

    // ── Échantillon en cours ──
    private var segBin = -1          // tranche de vitesse de l'échantillon, -1 : aucun
    private var segKwh = 0f
    private var segKm = 0f
    private var segMs = 0L
    private var segTempSum = 0f
    private var segTempN = 0
    private var segV0 = 0f           // vitesse au début et à la fin (élan)
    private var segV1 = 0f
    private var segAlt0: Double? = null
    private var altSmooth: Double? = null   // altitude GPS lissée sur ~30 s
    private var stillMs = 0L
    private var spoiled = false

    private fun reset() {
        segBin = -1; segKwh = 0f; segKm = 0f; segMs = 0L; segTempSum = 0f; segTempN = 0; stillMs = 0L; spoiled = false
    }

    /** Fin du trajet (voiture en veille, recharge) : l'échantillon en cours est abandonné. */
    fun interrupt() { reset(); altSmooth = null }

    /**
     * Une seconde de roulage, depuis EnergyMonitor : énergie de conduite nette (kWh, clim, accessoires et
     * réchauffage déduits, récupération déduite), durée, vitesse (km/h), température extérieure (°C).
     */
    fun tick(context: Context, driveKwh: Float, dtMs: Long, speedKmh: Float?, outside: Float?) {
        if (!enabled(context)) return
        // Réchauffage de la batterie : déduit par EnergyMonitor (puissance apprise) ; tant qu'elle ne l'est
        // pas, les échantillons où il peut chauffer sont écartés
        val ptcOn = EnergyMonitor.batteryHeatPossible && !EnergyMonitor.batteryHeatKnown
        val alt = app.mg4dash.map.LocationRepo.location.value?.takeIf { it.hasAltitude() }?.altitude
        if (alt != null) altSmooth = altSmooth?.let { it + (alt - it) * (dtMs.toDouble() / ALT_SMOOTH_MS).coerceAtMost(1.0) } ?: alt
        val v = speedKmh ?: 0f
        // Sortie de la tranche (avec une marge) : l'échantillon se ferme avant cette seconde
        if (segBin >= 0 && (v < segBin * SPEED_STEP - MARGIN_KMH || v >= (segBin + 1) * SPEED_STEP + MARGIN_KMH)) close(context)
        if (segBin < 0) { segBin = speedBin(v); segV0 = v; segAlt0 = altSmooth }
        if (ptcOn || speedKmh == null || outside == null) spoiled = true
        segKwh += driveKwh
        segMs += dtMs
        segKm += v * dtMs / 3_600_000f
        segV1 = v
        if (outside != null) { segTempSum += outside; segTempN++ }
        stillMs = if (v < 1f) stillMs + dtMs else 0L
        if (stillMs > STOP_MS) spoiled = true
        if (segKm >= SEGMENT_KM) close(context)
    }

    /** Ferme l'échantillon : gardé s'il a duré 15 s, corrigé de l'élan et du dénivelé. */
    private fun close(context: Context) {
        val km = segKm; val hours = segMs / 3_600_000f
        if (spoiled || segMs < SAMPLE_MS || km < 0.01f || segTempN == 0) { reset(); return }
        var kwh = segKwh
        // Élan : l'énergie mise dans la vitesse (ou rendue par elle) n'est pas de la consommation de roulage
        val v0 = segV0 / 3.6f; val v1 = segV1 / 3.6f
        val kinetic = 0.5f * MASS_KG * (v1 * v1 - v0 * v0) / 3_600_000f   // kWh
        kwh -= if (kinetic > 0) kinetic / UP_EFF else kinetic * DOWN_EFF
        // Dénivelé, sur l'altitude lissée (le bruit restant est aléatoire et s'annule sur beaucoup d'échantillons)
        val a0 = segAlt0; val a1 = altSmooth
        if (a0 != null && a1 != null) {
            val potential = MASS_KG * 9.81f * (a1 - a0).toFloat() / 3_600_000f   // kWh
            kwh -= if (potential > 0) potential / UP_EFF else potential * DOWN_EFF
        }
        val per100 = kwh / km * 100f
        // Bornes larges : sur des échantillons courts, élan et altitude ne se compensent qu'en moyenne ;
        // les couper d'un seul côté fausserait la case
        if (hours > 0f && per100 in -60f..120f) {
            val temp = segTempSum / segTempN
            val c = table(context)[segBin][tempBin(temp)]
            c.km += km; c.kwh += kwh; c.n++; c.sq += per100 * per100 * km
            save(context)
            if (app.mg4dash.system.Prefs(context).energyLog)
                Log.i(TAG, String.format(java.util.Locale.US, "sample %.0f s, %.2f km at %.0f km/h, %.1f °C: %.1f kWh/100 (cell %d now %.1f, %.0f km)",
                    segMs / 1000f, km, km / hours, temp, per100, c.n, c.per100 ?: 0f, c.km))
        }
        reset()
    }
}
