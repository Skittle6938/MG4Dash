package app.mg4dash.radar

import java.io.File
import kotlin.math.floor

/**
 * OpenStreetMap data of the alerter, by tile of [TILE]° (about 2.2 × 1.5 km here): the roads with their limits and
 * the speed cameras, read through Overpass and kept on disk [TTL_DAYS] days in a compact text form.
 *
 * Two levels: [Level.FULL] (all the roads a car drives on) fetched around the car, [Level.MAIN] (the main roads
 * only) for the trips prepared in advance over a wide area; a MAIN tile is replaced by a FULL one when the network
 * allows it.
 */
object RadarData {
    const val TILE = 0.02
    const val TTL_DAYS = 30
    const val VERSION = 1

    enum class Level { MAIN, FULL }

    /** Roads a car drives on (service roads left out: car parks, private ways). */
    const val FULL_ROADS = "motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link"
    const val MAIN_ROADS = "motorway|trunk|primary|secondary|tertiary|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link"

    val SERVERS = listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter")

    data class Key(val y: Int, val x: Int) {
        val south get() = y * TILE
        val west get() = x * TILE
        override fun toString() = "${y}_$x"
        companion object {
            fun of(lat: Double, lon: Double) = Key(floor(lat / TILE).toInt(), floor(lon / TILE).toInt())
            fun parse(s: String): Key? = s.split('_').takeIf { it.size == 2 }?.let { p -> p[0].toIntOrNull()?.let { y -> p[1].toIntOrNull()?.let { Key(y, it) } } }
        }
    }

    /** Tiles that cover [lat], [lon] ± [halfLat]°, [halfLon]°. */
    fun keysAround(lat: Double, lon: Double, halfLat: Double, halfLon: Double): List<Key> {
        val a = Key.of(lat - halfLat, lon - halfLon); val b = Key.of(lat + halfLat, lon + halfLon)
        return (a.y..b.y).flatMap { y -> (a.x..b.x).map { x -> Key(y, x) } }
    }

    /** One tile: its level, when it was fetched, its roads and cameras. */
    class Tile(val key: Key, val level: Level, val fetched: Long, val roads: List<Road>, val cameras: List<Camera>) {
        fun fresh(now: Long) = now - fetched < TTL_DAYS * 86_400_000L
    }

    /** Overpass query for the box [s], [w], [n], [e]: roads with their nodes and geometry, cameras, enforcement relations. */
    fun query(s: Double, w: Double, n: Double, e: Double, level: Level): String {
        val bb = String.format(java.util.Locale.US, "%.5f,%.5f,%.5f,%.5f", s, w, n, e)
        val roads = if (level == Level.FULL) FULL_ROADS else MAIN_ROADS
        return "[out:json][timeout:60];" +
            "way[\"highway\"~\"^($roads)$\"]($bb);out body geom qt;" +
            "node[\"highway\"=\"speed_camera\"]($bb);out body qt;" +
            "relation[\"type\"=\"enforcement\"]($bb)->.r;.r out body qt;node(r.r);out skel qt;"
    }

    // ── Parsing (org.json) ──

    /** Roads and cameras of an Overpass answer. */
    fun parse(json: String): Pair<List<Road>, List<Camera>> {
        val els = org.json.JSONObject(json).optJSONArray("elements") ?: return emptyList<Road>() to emptyList()
        val roads = ArrayList<Road>()
        val nodes = HashMap<Long, Pair<Double, Double>>()
        val camNodes = HashMap<Long, Pair<Pair<Double, Double>, Map<String, String>>>()
        val rels = ArrayList<org.json.JSONObject>()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val tags = tagsOf(e)
            when (e.optString("type")) {
                "way" -> parseRoad(e, tags)?.let { roads += it }
                "node" -> {
                    val p = e.optDouble("lat") to e.optDouble("lon")
                    nodes[e.getLong("id")] = p
                    if (tags["highway"] == "speed_camera") camNodes[e.getLong("id")] = p to tags
                }
                "relation" -> rels += e
            }
        }
        val cameras = ArrayList<Camera>()
        val done = HashSet<Long>()
        for (r in rels) {
            val tags = tagsOf(r)
            val enforcement = tags["enforcement"] ?: continue
            if (enforcement != "maxspeed" && enforcement != "average_speed" && enforcement != "traffic_signals") continue
            var device: Long? = null; var from: Long? = null; var to: Long? = null
            val members = r.optJSONArray("members") ?: continue
            for (k in 0 until members.length()) {
                val m = members.getJSONObject(k)
                if (m.optString("type") != "node") continue
                when (m.optString("role")) {
                    "device" -> device = device ?: m.getLong("ref")
                    "from" -> from = m.getLong("ref")
                    "to" -> to = m.getLong("ref")
                }
            }
            val id = device ?: continue
            val pos = camNodes[id]?.first ?: nodes[id] ?: continue
            val nodeTags = camNodes[id]?.second ?: emptyMap()
            val f = from?.let { nodes[it] }; val t = to?.let { nodes[it] }
            val bearing = if (f != null && t != null) Geo.bearing(f.first, f.second, t.first, t.second) else Zones.direction(nodeTags["direction"])
            val limit = DefaultLimits.parse(tags["maxspeed"]) ?: DefaultLimits.parse(nodeTags["maxspeed"])
            cameras += Camera(id, pos.first, pos.second, Zones.kind(enforcement, nodeTags), limit, bearing)
            done += id
        }
        for ((id, v) in camNodes) {
            if (id in done) continue
            val (pos, tags) = v
            cameras += Camera(id, pos.first, pos.second, Zones.kind(null, tags), DefaultLimits.parse(tags["maxspeed"]), Zones.direction(tags["direction"]))
        }
        return roads to cameras
    }

    private fun tagsOf(e: org.json.JSONObject): Map<String, String> {
        val t = e.optJSONObject("tags") ?: return emptyMap()
        val out = HashMap<String, String>()
        t.keys().forEach { k -> out[k] = t.optString(k) }
        return out
    }

    private fun parseRoad(e: org.json.JSONObject, tags: Map<String, String>): Road? {
        val ids = e.optJSONArray("nodes") ?: return null
        val geom = e.optJSONArray("geometry") ?: return null
        if (ids.length() != geom.length() || ids.length() < 2) return null
        val n = ids.length()
        val nodes = LongArray(n) { ids.getLong(it) }
        val lat = DoubleArray(n); val lon = DoubleArray(n)
        for (i in 0 until n) { val g = geom.optJSONObject(i) ?: return null; lat[i] = g.optDouble("lat"); lon[i] = g.optDouble("lon") }
        val hw = tags["highway"] ?: return null
        val oneway = when (tags["oneway"]) {
            "yes", "true", "1" -> 1
            "-1", "reverse" -> -1
            "no" -> 0
            else -> if (hw == "motorway" || hw == "motorway_link" || tags["junction"] == "roundabout") 1 else 0
        }
        return Road(e.getLong("id"), hw, nodes, lat, lon, DefaultLimits.parse(tags["maxspeed"]),
            DefaultLimits.parse(tags["maxspeed:forward"]), DefaultLimits.parse(tags["maxspeed:backward"]), oneway, tags["maxspeed:conditional"])
    }

    // ── Compact form on disk ──

    /** Writes a tile: a header line, then one line per road ("R|…") and per camera ("C|…"). */
    fun encode(t: Tile): String {
        val sb = StringBuilder()
        sb.append("V|$VERSION|${t.level}|${t.fetched}\n")
        fun n(v: Int?) = v?.toString() ?: ""
        for (r in t.roads) {
            sb.append("R|").append(r.id).append('|').append(r.highway).append('|').append(n(r.maxspeed)).append('|').append(n(r.forward))
                .append('|').append(n(r.backward)).append('|').append(r.oneway).append('|').append((r.conditional ?: "").replace('|', '/').replace('\n', ' '))
                .append('|').append(r.nodes.joinToString(","))
                .append('|')
            for (i in r.nodes.indices) {
                if (i > 0) sb.append(';')
                sb.append(String.format(java.util.Locale.US, "%.6f,%.6f", r.lat[i], r.lon[i]))
            }
            sb.append('\n')
        }
        for (c in t.cameras) {
            sb.append(String.format(java.util.Locale.US, "C|%d|%.6f|%.6f|%s|%s|%s\n", c.id, c.lat, c.lon, c.kind.name, n(c.maxspeed), c.bearing?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: ""))
        }
        return sb.toString()
    }

    fun decode(key: Key, text: String): Tile? {
        val lines = text.lineSequence().filter { it.isNotEmpty() }.toList()
        val head = lines.firstOrNull()?.split('|') ?: return null
        if (head.size < 4 || head[0] != "V" || head[1] != VERSION.toString()) return null
        val level = runCatching { Level.valueOf(head[2]) }.getOrNull() ?: return null
        val fetched = head[3].toLongOrNull() ?: return null
        val roads = ArrayList<Road>(); val cams = ArrayList<Camera>()
        for (l in lines.drop(1)) {
            val p = l.split('|')
            try {
                if (p[0] == "R" && p.size >= 10) {
                    val ids = p[8].split(',').map { it.toLong() }.toLongArray()
                    val pts = p[9].split(';').map { it.split(',') }
                    if (pts.size != ids.size) continue
                    roads += Road(p[1].toLong(), p[2], ids, DoubleArray(ids.size) { pts[it][0].toDouble() }, DoubleArray(ids.size) { pts[it][1].toDouble() },
                        p[3].toIntOrNull(), p[4].toIntOrNull(), p[5].toIntOrNull(), p[6].toIntOrNull() ?: 0, p[7].ifEmpty { null })
                } else if (p[0] == "C" && p.size >= 7) {
                    cams += Camera(p[1].toLong(), p[2].toDouble(), p[3].toDouble(), runCatching { CamKind.valueOf(p[4]) }.getOrDefault(CamKind.UNKNOWN),
                        p[5].toIntOrNull(), p[6].toFloatOrNull())
                }
            } catch (_: Exception) {}
        }
        return Tile(key, level, fetched, roads, cams)
    }

    /** Splits the roads and cameras of a wide box into its tiles (a road in each tile one of its points falls in). */
    fun split(keys: Collection<Key>, level: Level, fetched: Long, roads: List<Road>, cameras: List<Camera>): List<Tile> {
        val want = keys.toHashSet()
        val r = HashMap<Key, MutableList<Road>>(); val c = HashMap<Key, MutableList<Camera>>()
        for (road in roads) {
            val in_ = HashSet<Key>()
            for (i in road.nodes.indices) in_ += Key.of(road.lat[i], road.lon[i])
            in_.filter { it in want }.forEach { r.getOrPut(it) { ArrayList() } += road }
        }
        for (cam in cameras) Key.of(cam.lat, cam.lon).takeIf { it in want }?.let { c.getOrPut(it) { ArrayList() } += cam }
        return keys.map { Tile(it, level, fetched, r[it].orEmpty(), c[it].orEmpty()) }
    }

    // ── Disk ──

    fun dir(base: File) = File(base, "radar").apply { mkdirs() }

    fun read(base: File, key: Key): Tile? = File(dir(base), "$key.txt").takeIf { it.isFile }?.let { f ->
        runCatching { decode(key, f.readText()) }.getOrNull()
    }

    fun write(base: File, t: Tile) {
        val f = File(dir(base), "${t.key}.txt")
        val tmp = File(f.path + ".tmp")
        tmp.writeText(encode(t)); tmp.renameTo(f)
    }

    fun clear(base: File) { dir(base).listFiles()?.forEach { it.delete() } }

    fun size(base: File): Pair<Int, Long> = dir(base).listFiles()?.filter { it.name.endsWith(".txt") }?.let { fs -> fs.size to fs.sumOf { it.length() } } ?: (0 to 0L)
}
