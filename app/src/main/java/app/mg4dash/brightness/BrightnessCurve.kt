package app.mg4dash.brightness

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10

/**
 * The learnt curve of the smart brightness (Marc, 20/11): the light outside (log10 of the lux) → the screen's
 * brightness (0 to 1 of the system's range). Without any correction, the default curve: 15 % at night (10 lux), 35 % at
 * twilight (400), 70 % on a grey day (10 000), 100 % in full sun (80 000). Each correction made with the slider is a
 * point; the curve follows the points near them and stays the default far from them, never falls when the light rises,
 * and stays smooth (the light is only estimated). Pure: no Android, tested on its own.
 */
class BrightnessCurve(points: List<Point> = emptyList()) {
    /** A correction: the light then ([level], log10 lux), the lights ([lights]), the brightness wanted (0 to 1), its time. */
    data class Point(val level: Double, val lights: Boolean, val value: Double, val at: Long)

    val points: List<Point> = points.toList()

    /** The brightness (0 to 1) at the light [level] (log10 lux). */
    fun valueAt(level: Double): Double {
        // Between two steps of the table, straight
        val t = table()
        val x = (level.coerceIn(MIN_LEVEL, MAX_LEVEL) - MIN_LEVEL) / STEP
        val i0 = x.toInt().coerceIn(0, STEPS - 1); val f = x - i0
        return t[i0] + (t[i0 + 1] - t[i0]) * f
    }

    @Volatile private var cached: DoubleArray? = null

    /**
     * The curve on a table of the light scale: the default pulled towards each point, the more the nearer
     * (a bell [SPREAD] wide), then made never to fall (pool adjacent violators), then smoothed.
     */
    private fun table(): DoubleArray = cached ?: DoubleArray(STEPS + 1) { k ->
        val x = MIN_LEVEL + k * STEP
        var num = DEFAULT_WEIGHT * defaultAt(x); var den = DEFAULT_WEIGHT
        for (p in points) {
            val w = exp(-((x - p.level) / SPREAD).let { it * it })
            num += w * p.value; den += w
        }
        num / den
    }.let { raw ->
        // Only what the points change is smoothed: without any, exactly the default curve (its knees and its 100 %)
        val iso = isotonic(raw)
        val base = DoubleArray(STEPS + 1) { defaultAt(MIN_LEVEL + it * STEP) }
        val diff = smooth(DoubleArray(iso.size) { iso[it] - base[it] }, clamp = false)
        isotonic(DoubleArray(iso.size) { (base[it] + diff[it]).coerceIn(0.0, 1.0) }).also { cached = it }
    }

    /** A correction at [level] (lights [lights]) to [value] (0 to 1): replaces the point within [REPLACE] of it, the oldest goes beyond [MAX_POINTS]. */
    fun with(level: Double, lights: Boolean, value: Double, at: Long): BrightnessCurve {
        val kept = points.filter { abs(it.level - level) >= REPLACE }
        val all = (kept + Point(level, lights, value.coerceIn(0.0, 1.0), at)).sortedBy { it.at }.takeLast(MAX_POINTS)
        return BrightnessCurve(all)
    }

    /** [p] taken off (Settings, "Supprimer ce point"). */
    fun without(p: Point): BrightnessCurve = BrightnessCurve(points.filter { it != p })

    /**
     * [p] moved to [value] by hand (Settings, the curve dragged), kept between its neighbours' values so that the curve
     * still rises: never above the next point to the right, nor below the one to the left.
     */
    fun moved(p: Point, value: Double, at: Long): BrightnessCurve {
        val sorted = points.sortedBy { it.level }
        val i = sorted.indexOf(p).takeIf { it >= 0 } ?: return this
        val lo = sorted.getOrNull(i - 1)?.value ?: 0.0
        val hi = sorted.getOrNull(i + 1)?.value ?: 1.0
        return BrightnessCurve(points.map { if (it == p) it.copy(value = value.coerceIn(lo, hi).coerceIn(0.0, 1.0), at = at) else it })
    }

    /** The bounds a point at [level] may take by hand: between its neighbours' values. */
    fun bounds(level: Double, except: Point? = null): Pair<Double, Double> {
        val others = points.filter { it != except }
        return (others.filter { it.level < level }.maxByOrNull { it.level }?.value ?: 0.0) to
            (others.filter { it.level > level }.minByOrNull { it.level }?.value ?: 1.0)
    }

    fun encode(): String = JSONArray().apply {
        points.forEach { put(JSONObject().put("l", it.level).put("f", it.lights).put("v", it.value).put("t", it.at)) }
    }.toString()

    companion object {
        const val MIN_LEVEL = 0.0
        const val MAX_LEVEL = 5.0
        private const val STEPS = 100
        private const val STEP = (MAX_LEVEL - MIN_LEVEL) / STEPS
        /** How far a point pulls the curve (log10 lux): about a factor 3 of light each side. */
        const val SPREAD = 0.5
        /** The default's weight: a single point moves the curve most of the way where it is. */
        const val DEFAULT_WEIGHT = 0.25
        /** A new correction this near an old one (a factor 2 of light) replaces it. */
        const val REPLACE = 0.3
        const val MAX_POINTS = 12

        /** The default curve's points: (lux, brightness). */
        val DEFAULT = listOf(10.0 to 0.15, 400.0 to 0.35, 10_000.0 to 0.70, 80_000.0 to 1.0)

        /** The default curve at the light [level], straight between its points on the log scale, flat beyond them. */
        fun defaultAt(level: Double): Double {
            val pts = DEFAULT.map { log10(it.first) to it.second }
            if (level <= pts.first().first) return pts.first().second
            if (level >= pts.last().first) return pts.last().second
            val i = pts.indexOfLast { it.first <= level }
            val (x0, y0) = pts[i]; val (x1, y1) = pts[i + 1]
            return y0 + (y1 - y0) * (level - x0) / (x1 - x0)
        }

        fun decode(s: String?): BrightnessCurve = try {
            val a = JSONArray(s ?: "[]")
            BrightnessCurve((0 until a.length()).map { a.getJSONObject(it) }.map {
                Point(it.getDouble("l"), it.optBoolean("f"), it.getDouble("v"), it.optLong("t"))
            })
        } catch (_: Exception) { BrightnessCurve() }

        /** Never falling: pool adjacent violators. */
        internal fun isotonic(y: DoubleArray): DoubleArray {
            val vals = ArrayList<Double>(); val sizes = ArrayList<Int>()
            for (v in y) {
                vals += v; sizes += 1
                while (vals.size > 1 && vals[vals.size - 2] > vals[vals.size - 1]) {
                    val n = sizes[sizes.size - 2] + sizes[sizes.size - 1]
                    val m = (vals[vals.size - 2] * sizes[sizes.size - 2] + vals[vals.size - 1] * sizes[sizes.size - 1]) / n
                    vals.removeAt(vals.size - 1); sizes.removeAt(sizes.size - 1)
                    vals[vals.size - 1] = m; sizes[sizes.size - 1] = n
                }
            }
            val out = DoubleArray(y.size); var k = 0
            for (j in vals.indices) repeat(sizes[j]) { out[k++] = vals[j] }
            return out
        }

        /** Two passes of a moving average (9 steps, ±0.2 decade), which keep the curve never falling. */
        internal fun smooth(y: DoubleArray, clamp: Boolean = true): DoubleArray {
            fun pass(a: DoubleArray) = DoubleArray(a.size) { i ->
                val lo = maxOf(0, i - 4); val hi = minOf(a.size - 1, i + 4)
                (lo..hi).sumOf { a[it] } / (hi - lo + 1)
            }
            val s = pass(pass(y))
            return if (clamp) s.map { it.coerceIn(0.0, 1.0) }.toDoubleArray() else s
        }
    }
}
