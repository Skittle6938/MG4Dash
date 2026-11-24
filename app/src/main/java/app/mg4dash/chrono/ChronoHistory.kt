package app.mg4dash.chrono

import org.json.JSONArray
import org.json.JSONObject

/**
 * One measured run. Times in seconds: [split] the first target (0-50 / 0-30 mph, none for the reprise), [full] the
 * whole one (0-100 / 0-60 mph, or the reprise 80-120 / 50-70 mph). [curve]: highest kW at each whole km/h (NaN where
 * none); [trace]: the speed every 0.1 s from the start.
 */
data class ChronoRunData(
    val at: Long,
    val kind: ChronoMath.Kind,
    val mph: Boolean,
    val split: Float?,
    val full: Float,
    val soc: Int?,
    val outside: Float?,
    val climb: Float?,
    val maxKw: Float?,
    val maxKwAt: Float?,
    val distance: Float,
    val sport: Boolean,
    val climateCut: Boolean,
    val gps: Boolean,
    val curve: FloatArray,
    val trace: FloatArray,
) {
    override fun equals(other: Any?) = other is ChronoRunData && other.at == at && other.kind == kind
    override fun hashCode() = at.hashCode()
}

/**
 * The last [KEEP] runs, the newest first, and the all-time records kept apart, so a record never drops out with the
 * old runs. A record is the fastest time of its kind: 0-50, 0-100 and the reprise, in km/h and in mph apart.
 */
class ChronoHistory(runs: List<ChronoRunData> = emptyList(), records: Map<String, ChronoRunData> = emptyMap()) {
    var runs: List<ChronoRunData> = runs; private set
    var records: Map<String, ChronoRunData> = records; private set

    enum class Rec { SPLIT, FULL, ROLLING }

    /** The record of [r] for its unit, or null. */
    fun record(r: Rec, mph: Boolean): ChronoRunData? = records[key(r, mph)]

    /** Its time for [r], or null when the run has none. */
    fun time(run: ChronoRunData, r: Rec): Float? = when (r) {
        Rec.SPLIT -> run.split.takeIf { run.kind == ChronoMath.Kind.STANDING }
        Rec.FULL -> run.full.takeIf { run.kind == ChronoMath.Kind.STANDING }
        Rec.ROLLING -> run.full.takeIf { run.kind == ChronoMath.Kind.ROLLING }
    }

    /** Adds [run] (the newest) and returns the records it beat; a run downhill ([counts] false) beats none. */
    fun add(run: ChronoRunData): Set<Rec> {
        runs = (listOf(run) + runs.filter { it.at != run.at }).take(KEEP)
        val beaten = HashSet<Rec>()
        if (!counts(run)) return beaten
        val m = records.toMutableMap()
        for (r in Rec.entries) {
            val t = time(run, r) ?: continue
            val k = key(r, run.mph)
            val best = m[k]?.let { time(it, r) }
            if (best == null || t < best) { m[k] = run; beaten += r }
        }
        records = m
        return beaten
    }

    /** Removes one run; a record it held goes to the best run left, or none. */
    fun remove(at: Long) {
        runs = runs.filter { it.at != at }
        val m = records.toMutableMap()
        for ((k, v) in records) if (v.at == at) {
            val r = Rec.valueOf(k.substringBefore('_'))
            val mph = k.endsWith("_mph")
            val best = runs.filter { it.mph == mph && counts(it) }.mapNotNull { run -> time(run, r)?.let { run to it } }.minByOrNull { it.second }
            if (best != null) m[k] = best.first else m.remove(k)
        }
        records = m
    }

    /** Clears the runs; the records stay unless [records] too. */
    fun clear(records: Boolean) {
        runs = emptyList()
        if (records) this.records = emptyMap()
    }

    /** The last [n] full times of [kind] in [mph], oldest first (the card's bars). */
    fun lastTimes(kind: ChronoMath.Kind, mph: Boolean, n: Int = KEEP): List<ChronoRunData> =
        runs.filter { it.kind == kind && it.mph == mph }.take(n).reversed()

    fun toJson(): String = JSONObject().apply {
        put("runs", JSONArray().apply { runs.forEach { put(enc(it)) } })
        put("records", JSONObject().apply { records.forEach { (k, v) -> put(k, enc(v)) } })
    }.toString()

    companion object {
        const val KEEP = 10

        /** A run whose road went down this much or more (m, GPS) is kept but never a record nor a reference (24/11). */
        const val DOWNHILL_M = -2f

        /** It may be a record: not measured downhill (an unknown slope counts). */
        fun counts(run: ChronoRunData): Boolean = run.climb == null || run.climb > DOWNHILL_M

        fun key(r: Rec, mph: Boolean) = r.name + if (mph) "_mph" else "_kmh"

        fun fromJson(s: String?): ChronoHistory {
            if (s.isNullOrEmpty()) return ChronoHistory()
            return try {
                val o = JSONObject(s)
                val runs = o.optJSONArray("runs")?.let { a -> (0 until a.length()).mapNotNull { dec(a.optJSONObject(it)) } } ?: emptyList()
                val rec = o.optJSONObject("records")
                val records = HashMap<String, ChronoRunData>()
                rec?.keys()?.forEach { k -> dec(rec.optJSONObject(k))?.let { records[k] = it } }
                ChronoHistory(runs, records)
            } catch (_: Exception) { ChronoHistory() }
        }

        private fun floats(a: FloatArray) = JSONArray().apply { a.forEach { put(if (it.isNaN()) JSONObject.NULL else Math.round(it * 10) / 10.0) } }
        private fun floats(a: JSONArray?): FloatArray = if (a == null) FloatArray(0) else FloatArray(a.length()) { i -> if (a.isNull(i)) Float.NaN else a.optDouble(i).toFloat() }

        private fun JSONObject.f(k: String): Float? = if (!has(k) || isNull(k)) null else optDouble(k).toFloat().takeIf { !it.isNaN() }
        private fun JSONObject.i(k: String): Int? = if (!has(k) || isNull(k)) null else optInt(k)

        private fun enc(r: ChronoRunData) = JSONObject().apply {
            put("at", r.at); put("kind", r.kind.name); put("mph", r.mph)
            r.split?.let { put("split", it.toDouble()) }; put("full", r.full.toDouble())
            r.soc?.let { put("soc", it) }; r.outside?.let { put("out", it.toDouble()) }; r.climb?.let { put("climb", it.toDouble()) }
            r.maxKw?.let { put("kw", it.toDouble()) }; r.maxKwAt?.let { put("kwAt", it.toDouble()) }
            put("dist", r.distance.toDouble()); put("sport", r.sport); put("clim", r.climateCut); put("gps", r.gps)
            put("curve", floats(r.curve)); put("trace", floats(r.trace))
        }

        private fun dec(o: JSONObject?): ChronoRunData? {
            o ?: return null
            return try {
                ChronoRunData(
                    at = o.getLong("at"), kind = ChronoMath.Kind.valueOf(o.getString("kind")), mph = o.optBoolean("mph"),
                    split = o.f("split"), full = o.f("full") ?: return null, soc = o.i("soc"), outside = o.f("out"), climb = o.f("climb"),
                    maxKw = o.f("kw"), maxKwAt = o.f("kwAt"), distance = o.f("dist") ?: 0f, sport = o.optBoolean("sport"),
                    climateCut = o.optBoolean("clim"), gps = o.optBoolean("gps"), curve = floats(o.optJSONArray("curve")), trace = floats(o.optJSONArray("trace")),
                )
            } catch (_: Exception) { null }
        }
    }
}
