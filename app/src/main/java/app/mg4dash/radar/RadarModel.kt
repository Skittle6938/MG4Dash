package app.mg4dash.radar

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Speed camera alerter (test, 16/11), from OpenStreetMap data only: the roads with their limits, the speed
 * cameras and their enforcement relations around the car. Pure logic here, no Android: map-matching, the camera
 * zones, the alert levels.
 */

/** A road of the map: its nodes in order, its type and limits as tagged (km/h; null when untagged or not a number). */
class Road(
    val id: Long,
    val highway: String,
    val nodes: LongArray,
    val lat: DoubleArray,
    val lon: DoubleArray,
    val maxspeed: Int? = null,
    val forward: Int? = null,
    val backward: Int? = null,
    /** 1 one way along the nodes, -1 against them, 0 both ways. */
    val oneway: Int = 0,
    val conditional: String? = null,
)

/** What a camera checks. */
enum class CamKind { FIXED, RED_LIGHT, SECTION, DISCRIMINANT, UNKNOWN }

/**
 * A speed camera: its position, what it checks, its limit if tagged (node or enforcement relation), and the direction
 * of the traffic it checks ([bearing] in degrees, null when both ways or unknown).
 */
class Camera(val id: Long, val lat: Double, val lon: Double, val kind: CamKind, val maxspeed: Int?, val bearing: Float?)

object Geo {
    /** Metres per degree of longitude at [lat]. */
    fun mPerLon(lat: Double) = 111_320.0 * cos(Math.toRadians(lat))
    const val M_PER_LAT = 110_540.0

    fun dist(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
        hypot((lon2 - lon1) * mPerLon((lat1 + lat2) / 2), (lat2 - lat1) * M_PER_LAT)

    /** Bearing from the first point to the second, 0..360 (0 north, 90 east). */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val dx = (lon2 - lon1) * mPerLon((lat1 + lat2) / 2)
        val dy = (lat2 - lat1) * M_PER_LAT
        val b = Math.toDegrees(atan2(dx, dy)).toFloat()
        return (b + 360f) % 360f
    }

    /** Smallest angle between two bearings, 0..180. */
    fun angle(a: Float, b: Float): Float { val d = abs(((a - b) % 360f + 360f) % 360f); return if (d > 180f) 360f - d else d }

    /** Distance from a point to the segment a–b (m), and where along it its foot falls (0..1). */
    fun toSegment(lat: Double, lon: Double, aLat: Double, aLon: Double, bLat: Double, bLon: Double): Pair<Double, Double> {
        val k = mPerLon(lat)
        val ax = (aLon - lon) * k; val ay = (aLat - lat) * M_PER_LAT
        val bx = (bLon - lon) * k; val by = (bLat - lat) * M_PER_LAT
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 < 1e-9) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(ax + t * dx, ay + t * dy) to t
    }

}

/** Countries with their own default limits; the others use the French ones for now. */
enum class Country { FR, IT, GB, OTHER;
    companion object {
        /** Rough outlines (lat, lon pairs): enough to tell the country of a road, not a border. */
        private val GB_OUTLINE = doubleArrayOf(49.85, -6.5, 49.95, -3.0, 50.45, -1.0, 50.75, 0.8, 51.0, 1.45, 51.4, 1.65, 52.0, 2.0, 53.5, 0.8,
            55.5, -1.0, 58.7, -2.5, 60.9, -0.7, 60.9, -3.0, 58.5, -7.8, 56.5, -7.8, 55.4, -6.2, 55.3, -7.4, 54.6, -8.2, 54.0, -7.3,
            54.0, -6.0, 53.3, -4.7, 52.0, -5.4, 51.5, -5.6)
        private val IT_OUTLINE = doubleArrayOf(47.1, 10.5, 46.6, 12.2, 46.4, 13.7, 45.6, 13.9, 44.0, 12.7, 42.1, 14.8, 41.9, 16.2, 40.5, 18.6,
            39.8, 18.5, 40.1, 17.0, 39.5, 16.6, 38.9, 17.2, 37.9, 16.1, 37.9, 15.6, 38.3, 15.6, 40.0, 15.5, 41.2, 13.0, 42.4, 11.1,
            43.8, 10.2, 44.1, 9.6, 44.4, 8.5, 43.78, 7.53, 44.1, 6.95, 45.1, 6.6, 45.9, 7.0, 46.5, 8.4, 46.0, 9.0, 46.5, 9.5, 46.6, 10.2)
        private val SICILY = doubleArrayOf(38.35, 12.3, 38.35, 15.7, 36.6, 15.2, 37.5, 12.3)
        private val SARDINIA = doubleArrayOf(41.35, 9.2, 41.0, 9.9, 39.0, 9.7, 38.85, 8.3, 40.95, 8.05)
        private val FR_BOX = doubleArrayOf(41.3, 51.2, -5.3, 9.7)

        private fun inside(poly: DoubleArray, lat: Double, lon: Double): Boolean {
            var inside = false
            var j = poly.size - 2
            var i = 0
            while (i < poly.size) {
                val yi = poly[i]; val xi = poly[i + 1]; val yj = poly[j]; val xj = poly[j + 1]
                if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
                j = i; i += 2
            }
            return inside
        }

        fun of(lat: Double, lon: Double): Country = when {
            inside(GB_OUTLINE, lat, lon) -> GB
            inside(IT_OUTLINE, lat, lon) || inside(SICILY, lat, lon) || inside(SARDINIA, lat, lon) -> IT
            lat in FR_BOX[0]..FR_BOX[1] && lon in FR_BOX[2]..FR_BOX[3] -> FR
            else -> OTHER
        }
    }
}

/** Limits by road type when the road has no maxspeed tag, by country (France's elsewhere for now): shown as estimated. */
object DefaultLimits {
    private const val MPH = 1.609344

    private fun mph(v: Int) = (v * MPH).roundToInt()

    fun of(highway: String, country: Country = Country.FR, oneway: Int = 0): Int? = when (country) {
        Country.GB -> when (highway) {
            "motorway", "motorway_link" -> mph(70)
            // A dual carriageway (one way each) has 70 mph, a single one 60 mph
            "trunk", "primary" -> mph(if (oneway != 0) 70 else 60)
            "trunk_link", "primary_link", "secondary", "tertiary", "unclassified", "secondary_link", "tertiary_link" -> mph(60)
            "residential" -> mph(30)
            "living_street", "service" -> mph(20)
            else -> null
        }
        Country.IT -> when (highway) {
            "motorway" -> 130
            "motorway_link" -> 80
            "trunk" -> 110
            "trunk_link" -> 70
            "primary", "secondary", "tertiary", "unclassified", "primary_link", "secondary_link", "tertiary_link" -> 90
            "residential" -> 50
            "living_street" -> 30
            "service" -> 30
            else -> null
        }
        else -> when (highway) {
            "motorway" -> 130
            "motorway_link" -> 90
            "trunk" -> 110
            "trunk_link" -> 70
            "primary", "secondary", "tertiary", "unclassified", "primary_link", "secondary_link", "tertiary_link" -> 80
            "residential" -> 50
            "living_street" -> 20
            "service" -> 30
            else -> null
        }
    }

    /** Default limit of [r] where it is. */
    fun of(r: Road): Int? = of(r.highway, Country.of(r.lat[0], r.lon[0]), r.oneway)

    /**
     * A maxspeed tag in km/h: "50", "30 mph", "FR:urban", "IT:rural", "GB:nsl_single" and the like, "walk"; null when
     * it is not a speed ("none", "signals", a variable sign).
     */
    fun parse(tag: String?): Int? {
        val t = tag?.trim()?.lowercase() ?: return null
        if (t.isEmpty()) return null
        Regex("^(\\d+(?:\\.\\d+)?)\\s*(mph|km/h|kmh|kph)?$").find(t)?.let { m ->
            val v = m.groupValues[1].toDouble()
            return if (m.groupValues[2] == "mph") (v * MPH).roundToInt() else v.roundToInt()
        }
        if (t == "walk") return 6
        val country = t.substringBefore(':', "")
        val zone = t.substringAfter(':', "")
        return when (zone) {
            "urban" -> if (country == "gb" || country == "uk") mph(30) else 50
            "rural" -> if (country == "it") 90 else 80
            "motorway" -> if (country == "gb" || country == "uk") mph(70) else 130
            "trunk" -> 110
            "nsl_single" -> mph(60)
            "nsl_dual", "nsl_restricted" -> if (zone == "nsl_dual") mph(70) else mph(30)
            "zone30", "zone:30" -> 30
            "zone20", "zone:20" -> 20
            "living_street" -> if (country == "it") 30 else 20
            else -> null
        }
    }
}

/** The road the car is on, and in which direction along its nodes. */
class Match(val road: Road, val forward: Boolean, val seg: Int, val t: Double, val dist: Double) {
    /** The limit for this direction, null when untagged. */
    val tagged: Int? get() = (if (forward) road.forward else road.backward) ?: road.maxspeed
    val limit: Int? get() = tagged ?: DefaultLimits.of(road)
    val estimated: Boolean get() = tagged == null
}

/**
 * Map-matching: the nearest road within [MAX_DIST] m whose direction follows the heading (±[MAX_ANGLE]°), keeping the
 * previous road while it is still one of them. A connected continuation can take over at the endpoint;
 * ambiguous changes wait [SWITCH_MS].
 */
class Matcher {
    companion object {
        const val MAX_DIST = 25.0
        const val MAX_ANGLE = 35f
        const val SWITCH_MS = 3_000L
        /** The previous road wins over a nearer one by up to this many metres (parallel carriageways, ramps). */
        const val KEEP_BONUS = 8.0
    }

    var current: Match? = null
        private set
    private var pending: Match? = null
    private var pendingSince = 0L

    /** Candidates near the point, best first. */
    fun candidates(roads: Collection<Road>, lat: Double, lon: Double, heading: Float?): List<Match> {
        val out = ArrayList<Match>()
        for (r in roads) {
            var best: Match? = null
            for (i in 0 until r.nodes.size - 1) {
                val (d, t) = Geo.toSegment(lat, lon, r.lat[i], r.lon[i], r.lat[i + 1], r.lon[i + 1])
                if (d > MAX_DIST || (best != null && d >= best.dist)) continue
                val segB = Geo.bearing(r.lat[i], r.lon[i], r.lat[i + 1], r.lon[i + 1])
                val fwd = when {
                    heading == null -> r.oneway >= 0
                    Geo.angle(segB, heading) <= MAX_ANGLE -> true
                    Geo.angle((segB + 180f) % 360f, heading) <= MAX_ANGLE -> false
                    else -> null
                } ?: continue
                if ((fwd && r.oneway < 0) || (!fwd && r.oneway > 0)) continue
                best = Match(r, fwd, i, t, d)
            }
            best?.let { out += it }
        }
        out.sortBy { it.dist }
        return out
    }

    /** New position: the road matched now (null when none is near). */
    fun update(roads: Collection<Road>, lat: Double, lon: Double, heading: Float?, now: Long): Match? {
        val cands = candidates(roads, lat, lon, heading)
        val cur = current
        val same = cur?.let { c -> cands.firstOrNull { it.road.id == c.road.id && it.forward == c.forward } }
        val best = cands.firstOrNull()
        if (best == null) { current = null; pending = null; return null }
        if (cur == null) { current = best; pending = null; return best }
        if (heading != null && (same == null || best.dist < same.dist) && followsEndpoint(same ?: cur, best, same != null)) {
            current = best; pending = null; return best
        }
        if (same != null && same.dist <= best.dist + KEEP_BONUS) { current = same; pending = null; return same }
        // Another road: taken once it has stayed the best for a while (the previous one meanwhile, if still near)
        if (pending?.road?.id != best.road.id || pending?.forward != best.forward) { pending = best; pendingSince = now }
        if (now - pendingSince >= SWITCH_MS) {
            current = best; pending = null; return best
        }
        current = same ?: cur
        return current
    }

    private fun followsEndpoint(current: Match, next: Match, stillNear: Boolean): Boolean {
        val nodes = current.road.nodes
        val exitSegment = if (current.forward) nodes.size - 2 else 0
        val exitFraction = if (current.forward) 1.0 else 0.0
        if (current.seg != exitSegment || (stillNear && current.t != exitFraction)) return false
        val exitNode = if (current.forward) nodes.last() else nodes.first()
        val nextNodes = next.road.nodes
        return if (next.forward) next.seg == 0 && next.t > 0.0 && nextNodes.first() == exitNode
            else next.seg == nextNodes.size - 2 && next.t < 1.0 && nextNodes.last() == exitNode
    }

    fun reset() { current = null; pending = null }
}

/** Zones around the cameras: the exact position is never shown, only a stretch of road that contains it. */
object Zones {
    /** Length of the zone of a camera whose limit is [limit] on a road of type [highway] (the owner's rule, 16/11). */
    fun length(limit: Int?, highway: String?): Int = when {
        limit != null && limit <= 50 -> 500
        (highway == "motorway" || highway == "trunk") && (limit ?: 0) >= 110 -> 4000
        else -> 2000
    }

    /** Share of the zone before the camera; the rest comes after it. */
    const val BEFORE = 0.7

    /** The camera checks the traffic going along [heading]: no direction tagged, or within 90° of it. */
    fun sameWay(cam: Camera, heading: Float?): Boolean =
        cam.bearing == null || heading == null || Geo.angle(cam.bearing, heading) <= 90f

    /** Kind from the OSM tags of a speed_camera node and its enforcement relation. */
    fun kind(enforcement: String?, nodeTags: Map<String, String>): CamKind = when {
        enforcement == "traffic_signals" || nodeTags["speed_camera"] == "traffic_signals" || nodeTags["enforcement"] == "traffic_signals" -> CamKind.RED_LIGHT
        enforcement == "average_speed" || nodeTags["speed_camera"] == "average_speed" || nodeTags["enforcement"] == "average_speed" -> CamKind.SECTION
        nodeTags["speed_camera"] == "discriminant" || nodeTags["hgv"] != null && nodeTags["maxspeed:hgv"] != null -> CamKind.DISCRIMINANT
        enforcement == "maxspeed" || nodeTags["highway"] == "speed_camera" -> CamKind.FIXED
        else -> CamKind.UNKNOWN
    }

    /** A direction tag in degrees: "180", "N", "NE"… (null for "both", "forward"… and anything else). */
    fun direction(tag: String?): Float? {
        val t = tag?.trim()?.uppercase() ?: return null
        t.toFloatOrNull()?.let { return (it % 360f + 360f) % 360f }
        val names = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        val i = names.indexOf(t)
        return if (i >= 0) i * 22.5f else null
    }
}

/** Alert level: none, light overspeed (orange), strong (red). */
enum class Level { NONE, LIGHT, STRONG }

/**
 * Alert thresholds: [low] and [high] over the limit, in the display unit (km/h or mph) or in % of the limit; the level
 * goes down again only [HYSTERESIS_KMH] km/h under the threshold it crossed.
 */
class Thresholds(val low: Float, val high: Float, val percent: Boolean, val miles: Boolean) {
    companion object {
        const val HYSTERESIS_KMH = 2f
        const val KM_PER_MI = 1.609344f
        const val DEFAULT_LOW = 1f
        const val DEFAULT_HIGH = 6f
    }

    /** A threshold as km/h over [limitKmh]. */
    fun marginKmh(value: Float, limitKmh: Int): Float = when {
        percent -> limitKmh * value / 100f
        miles -> value * KM_PER_MI
        else -> value
    }

    fun level(speedKmh: Float, limitKmh: Int?, previous: Level): Level {
        if (limitKmh == null) return Level.NONE
        val over = speedKmh - limitKmh
        val lo = marginKmh(low, limitKmh); val hi = marginKmh(high, limitKmh)
        val h = HYSTERESIS_KMH
        return when {
            over >= hi -> Level.STRONG
            previous == Level.STRONG && over >= hi - h -> Level.STRONG
            over >= lo -> Level.LIGHT
            previous != Level.NONE && over >= lo - h -> Level.LIGHT
            else -> Level.NONE
        }
    }
}

/** Speeds shown: km/h to the display unit and back. */
object Speeds {
    fun toUnit(kmh: Float, miles: Boolean): Int = (if (miles) kmh / Thresholds.KM_PER_MI else kmh).roundToInt()
    fun toKmh(v: Float, miles: Boolean): Float = if (miles) v * Thresholds.KM_PER_MI else v
}
