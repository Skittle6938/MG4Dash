package app.mg4dash.energy

import android.content.Context
import app.mg4dash.car.CarProps
import app.mg4dash.system.Log
import app.mg4dash.system.Prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Usable battery capacity and estimated health (SOH), with no manual setting if possible.
 *
 * Capacity:
 *  1) the value announced by the car (INFO_EV_BATTERY_CAPACITY, AOSP, in Wh; MG4Control found
 *     it at 0 on SWI133, it may answer elsewhere);
 *  2) otherwise, MG4Dash's measurement, made while driving (discharge): net energy out of the battery
 *     (regeneration deducted) ÷ drop in %, counted only when MG4Dash sees both (screen
 *     on). The % lost with the car closed, and charging, are outside the measurement: the drop adds up over
 *     several trips and days, without waiting for a charge, and the total is kept with the
 *     preferences (kept across updates). From 15 % of total drop, the measurement joins the
 *     history, dated; smoothing: average weighted by the % drop, over the last 30 days.
 * Health: measured capacity ÷ the model's original usable capacity (51, 64 or 77 kWh), also smoothed
 * over 30 days, and over the whole measured period to follow the trend.
 *
 * Up to 1.1.3, the measurement ran from one charge to the next: it counted the % lost with the car
 * closed without the matching energy, and did not end if the charge was seen % by %.
 */
object BatteryCapacity {
    private const val TAG = "Capacity"
    private const val PROP_EV_BATTERY_CAPACITY = 0x11600106
    const val MIN_DROP = 15f
    private const val MONTH_MS = 30L * 24 * 3600_000L
    private const val MAX_SAMPLES = 300

    /** Models: gross capacity (displayed) → default new usable capacity (kWh), unofficial values (#77). */
    val MODELS = listOf(51 to 50.8f, 64 to 61.7f, 77 to 74.4f)

    /** One measurement: date, capacity (kWh), % drop and km driven during the measurement (0 before 1.1.5). */
    data class Sample(val at: Long, val kwh: Float, val drop: Int, val km: Float = 0f)

    /** Capacity announced by the car, in kWh, or null. */
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

    /** Measured capacity, smoothed over 30 days (otherwise all measurements), and number of measurements. */
    fun estimate(context: Context): Pair<Float, Int>? {
        val all = samples(context)
        val recent = all.filter { System.currentTimeMillis() - it.at < MONTH_MS }
        weighted(recent)?.let { return it to recent.size }
        weighted(all)?.let { return it to all.size }
        return null
    }

    // Current measurement: total net energy (kWh) and % drop, kept in memory and
    // saved by [flush] (every 30 s by EnergyMonitor).
    private var accKwh = 0f
    private var accDrop = 0f
    private var accKm = 0f
    private var loaded = false

    private fun load(context: Context) {
        if (loaded) return
        val sp = Prefs(context).raw
        accKwh = sp.getFloat("soh_kwh", 0f); accDrop = sp.getFloat("soh_drop", 0f); accKm = sp.getFloat("soh_km", 0f)
        // Old keys (measurement from one charge to the next): no longer used
        if (sp.contains("cap_current")) sp.edit().remove("cap_current").remove("cap_current_drop").apply()
        loaded = true
    }

    @Synchronized
    fun flush(context: Context) {
        if (!loaded) return
        Prefs(context).raw.edit().putFloat("soh_kwh", accKwh).putFloat("soh_drop", accDrop).putFloat("soh_km", accKm).apply()
    }

    /**
     * One observed second: [kwh] = energy out of the battery (negative when regenerating),
     * [drop] = % drop over the same second (negative if the % goes up), [km] = distance driven.
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

    /** Current measurement: total drop (%), provisional capacity (kWh, from 5 %) and km driven. */
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

    /** Reference new usable capacity (kWh): the one entered by hand (#77), otherwise the chosen model's. */
    fun nominal(context: Context): Float = Prefs(context).batteryNewKwh.takeIf { it > 0f } ?: modelNominal(context)

    /** Default new usable capacity of the chosen model (kWh). */
    fun modelNominal(context: Context): Float {
        val gross = Prefs(context).batteryModel
        return MODELS.firstOrNull { it.first == gross }?.second ?: 50.8f
    }

    /** Health (%, to the tenth) over the measurements of the last 30 days (otherwise all), and km they cover. */
    data class Health(val percent: Float, val km: Float, val count: Int)

    fun health(context: Context): Health? {
        val all = samples(context).takeIf { it.isNotEmpty() } ?: return null
        val recent = all.filter { System.currentTimeMillis() - it.at < MONTH_MS }.ifEmpty { all }
        val m = weighted(recent) ?: return null
        return Health((m / nominal(context) * 100f).coerceAtMost(100f), recent.sumOf { it.km.toDouble() }.toFloat(), recent.size)
    }

    /**
     * Current capacity (wear included): the car's, otherwise MG4Dash's measurement. No more manual
     * setting: it froze by itself at the first measurement (the key existed) and the measurement was no longer followed.
     */
    fun apply(context: Context) {
        val prefs = Prefs(context)
        val v = fromCar() ?: estimate(context)?.first ?: return
        if (Math.abs(v - prefs.batteryKwh) >= 0.1f) {
            prefs.batteryKwh = Math.round(v * 10f) / 10f
            Log.i(TAG, "capacity used: ${prefs.batteryKwh} kWh")
        }
    }
}
