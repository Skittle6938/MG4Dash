package app.mg4dash.radar

/**
 * The road ahead of the car, followed from the road it is on through the roads that continue it (the one going
 * straightest at each end), up to [MAX_M] metres: its limits along the way and the camera zones on it.
 */
object Ahead {
    const val MAX_M = 5_000.0
    /** A camera this close to the road ahead is on it. */
    const val ON_ROAD_M = 30.0
    /** At the end of a road, the next one must go on within this angle. */
    const val MAX_TURN = 60f

    /** A point of the road ahead: [s] metres from the car; [limit] applies from it on. */
    class Point(val lat: Double, val lon: Double, val s: Double, val limit: Int?, val estimated: Boolean, val highway: String)

    /** Roads by node, to find those that go on from the end of one. */
    class Graph(roads: Collection<Road>) {
        val byNode = HashMap<Long, MutableList<Road>>()
        init { roads.forEach { r -> r.nodes.forEach { n -> byNode.getOrPut(n) { ArrayList(2) } += r } } }
    }

    private fun limitOf(r: Road, fwd: Boolean): Pair<Int?, Boolean> {
        val t = (if (fwd) r.forward else r.backward) ?: r.maxspeed
        return (t ?: DefaultLimits.of(r)) to (t == null)
    }

    /** The road ahead from [lat], [lon] on [m]. */
    fun path(m: Match, lat: Double, lon: Double, graph: Graph, maxM: Double = MAX_M): List<Point> {
        val out = ArrayList<Point>()
        var road = m.road; var fwd = m.forward
        var (lim, est) = limitOf(road, fwd)
        out += Point(lat, lon, 0.0, lim, est, road.highway)
        var s = 0.0
        var pLat = lat; var pLon = lon
        // Next node from the car's segment, in the direction of travel
        var i = if (fwd) m.seg + 1 else m.seg
        val visited = HashSet<Long>()
        visited += road.id
        while (s < maxM) {
            val last = if (fwd) road.nodes.size - 1 else 0
            while (true) {
                val d = Geo.dist(pLat, pLon, road.lat[i], road.lon[i])
                s += d; pLat = road.lat[i]; pLon = road.lon[i]
                out += Point(pLat, pLon, s, lim, est, road.highway)
                if (i == last || s >= maxM) break
                i += if (fwd) 1 else -1
            }
            if (s >= maxM) break
            // End of this road: the one that goes on straightest
            val endNode = road.nodes[last]
            val prev = if (fwd) last - 1 else last + 1
            val inB = Geo.bearing(road.lat[prev], road.lon[prev], road.lat[last], road.lon[last])
            var best: Triple<Road, Boolean, Int>? = null; var bestTurn = MAX_TURN
            for (r in graph.byNode[endNode].orEmpty()) {
                if (r.id in visited) continue
                val k = r.nodes.indexOf(endNode)
                for (dir in listOf(true, false)) {
                    val nk = if (dir) k + 1 else k - 1
                    if (nk < 0 || nk >= r.nodes.size) continue
                    if ((dir && r.oneway < 0) || (!dir && r.oneway > 0)) continue
                    val turn = Geo.angle(inB, Geo.bearing(r.lat[k], r.lon[k], r.lat[nk], r.lon[nk]))
                    if (turn < bestTurn) { bestTurn = turn; best = Triple(r, dir, nk) }
                }
            }
            val (nr, nd, ni) = best ?: break
            road = nr; fwd = nd; i = ni; visited += nr.id
            limitOf(road, fwd).let { lim = it.first; est = it.second }
            // The point at the junction carries the new road's limit from there on
            out[out.size - 1] = Point(pLat, pLon, s, lim, est, road.highway)
        }
        return out
    }

    /** First change of limit along [path]: its distance and the new limit (null when none within the path). */
    fun nextLimit(path: List<Point>): Pair<Double, Int>? {
        val first = path.firstOrNull()?.limit
        val p = path.firstOrNull { it.limit != null && it.limit != first } ?: return null
        return p.s to p.limit!!
    }

    /** Where [lat], [lon] falls on [path]: distance along it, distance off it, and the road's bearing there. */
    fun locate(path: List<Point>, lat: Double, lon: Double): Triple<Double, Double, Float>? {
        var best: Triple<Double, Double, Float>? = null
        for (k in 0 until path.size - 1) {
            val a = path[k]; val b = path[k + 1]
            if (b.s - a.s < 0.01) continue
            val (d, t) = Geo.toSegment(lat, lon, a.lat, a.lon, b.lat, b.lon)
            if (best == null || d < best.second) best = Triple(a.s + t * (b.s - a.s), d, Geo.bearing(a.lat, a.lon, b.lat, b.lon))
        }
        return best
    }

    /** A camera zone on the road ahead: from [start] to [end] metres from the car (start ≤ 0: the car is in it). */
    class Zone(val cam: Camera, val start: Double, val end: Double, val limit: Int?, val length: Int)

    /**
     * The zones of the [cameras] on the road ahead that check the traffic going our way (the road's direction where
     * the camera stands), nearest first.
     */
    fun zones(path: List<Point>, cameras: Collection<Camera>): List<Zone> {
        if (path.size < 2) return emptyList()
        val out = ArrayList<Zone>()
        for (c in cameras) {
            val (s, off, dir) = locate(path, c.lat, c.lon) ?: continue
            if (!Zones.sameWay(c, dir)) continue
            if (off > ON_ROAD_M || s <= 0.5) continue
            val at = path.lastOrNull { it.s <= s } ?: path.first()
            val limit = c.maxspeed ?: at.limit
            val len = Zones.length(limit, at.highway)
            out += Zone(c, s - Zones.BEFORE * len, s + (1 - Zones.BEFORE) * len, limit, len)
        }
        out.sortBy { it.start }
        return out
    }
}
