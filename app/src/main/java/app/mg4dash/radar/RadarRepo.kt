package app.mg4dash.radar

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import app.mg4dash.BuildConfig
import app.mg4dash.car.CarBridge
import app.mg4dash.map.LocationRepo
import app.mg4dash.system.DataUsage
import app.mg4dash.system.Log
import app.mg4dash.system.Prefs
import app.mg4dash.system.Units
import app.mg4dash.system.WifiFirst
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The alerter at work while its card is in a place of the home screen: follows the car on the OpenStreetMap roads,
 * fetches the tiles around it when the network allows, and publishes what the card and the alerts show.
 */
object RadarRepo {
    private const val TAG = "Radar"
    /** Area kept loaded around the car (degrees): about ±1.3 km north–south, ±1.25 km east–west. */
    private const val HALF_LAT = 0.012
    private const val HALF_LON = 0.017
    /** Below this speed the GPS heading is not trusted. */
    private const val MIN_HEADING_KMH = 6f
    /** A failed fetch waits this long before the next try. */
    private const val BACKOFF_MS = 60_000L
    /** Prepared trips: radius around the car (km). */
    const val PREPARE_KM = 30.0

    enum class Status { OK, NO_POSITION, LOADING, NO_DATA, WIFI_ONLY, OFF_ROAD }

    /** The zone the car is in: what checks it, the speed checked, metres left to its end. */
    class InZone(val kind: CamKind, val limit: Int?, val toEnd: Double)

    /** A zone ahead: metres to its start. */
    class NextZone(val kind: CamKind, val limit: Int?, val dist: Double)

    data class State(
        val status: Status = Status.NO_POSITION,
        val speedKmh: Float? = null,
        val limit: Int? = null,
        val estimated: Boolean = false,
        val level: Level = Level.NONE,
        val zone: InZone? = null,
        val next: List<NextZone> = emptyList(),
        val nextLimit: Pair<Double, Int>? = null,
        val highway: String? = null,
    ) {
        /** Over the high threshold inside a camera zone: the very strong alert. */
        val critical: Boolean get() = zone != null && level == Level.STRONG
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** Prepared trips: progress (done, total), null when not running; [prepareResult] the last outcome. */
    private val _prepare = MutableStateFlow<Pair<Int, Int>?>(null)
    val prepare: StateFlow<Pair<Int, Int>?> = _prepare

    private var ctx: Context? = null
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val worker: Handler by lazy { Handler(HandlerThread("radar").apply { start() }.looper) }
    private val net: Handler by lazy { Handler(HandlerThread("radar-net").apply { start() }.looper) }

    // Worker thread state
    private val tiles = HashMap<RadarData.Key, RadarData.Tile>()
    private val fetching = HashSet<RadarData.Key>()
    private var failedAt = 0L
    private var roads: Collection<Road> = emptyList()
    private var cameras: Collection<Camera> = emptyList()
    private var graph = Ahead.Graph(emptyList())
    private var loadedKeys: Set<RadarData.Key> = emptySet()
    private val matcher = Matcher()
    private var last: Location? = null
    private var odo = 0.0
    /** The zone the car is in: its camera and where it ends on [odo]. */
    private var zoneCam: Camera? = null
    private var zoneEnd = 0.0
    private var zoneLimit: Int? = null
    private var level = Level.NONE

    val running: Boolean get() = job != null

    /** Starts following the car (the card placed); no effect when already running. */
    fun start(context: Context) {
        if (job != null) return
        ctx = context.applicationContext
        Log.i(TAG, "alerter on")
        job = scope.launch {
            LocationRepo.location.collect { loc -> if (loc != null) worker.post { onLocation(loc) } }
        }
        RadarAlert.start(context.applicationContext)
    }

    fun stop() {
        if (job == null) return
        job?.cancel(); job = null
        RadarAlert.stop()
        worker.post { tiles.clear(); roads = emptyList(); cameras = emptyList(); graph = Ahead.Graph(emptyList()); loadedKeys = emptySet(); matcher.reset(); zoneCam = null }
        _state.value = State()
        Log.i(TAG, "alerter off")
    }

    /** Wi-Fi only and not on a Wi-Fi now: no fetch. */
    private fun netAllowed(c: Context): Boolean = !Prefs(c).radarWifiOnly || onWifi()
    private fun onWifi(): Boolean = WifiFirst.bound != null

    // ── Worker thread ──

    private fun onLocation(loc: Location) {
        val c = ctx ?: return
        val now = System.currentTimeMillis()
        val speed = CarBridge.state.value.speedKmh?.takeIf { it >= 0f } ?: loc.takeIf { it.hasSpeed() }?.speed?.times(3.6f)
        last?.let { p -> val d = Geo.dist(p.latitude, p.longitude, loc.latitude, loc.longitude); if (d < 300) odo += d }
        last = loc
        val heading = loc.takeIf { it.hasBearing() && (speed ?: 0f) >= MIN_HEADING_KMH }?.bearing
        ensureTiles(c, loc.latitude, loc.longitude, now)
        if (loadedKeys.isEmpty() && fetching.isEmpty()) {
            publish(State(status = if (!netAllowed(c)) Status.WIFI_ONLY else Status.NO_DATA, speedKmh = speed)); return
        }
        if (roads.isEmpty()) { publish(State(status = Status.LOADING, speedKmh = speed)); return }
        val m = matcher.update(roads, loc.latitude, loc.longitude, heading, android.os.SystemClock.elapsedRealtime())
        if (m == null) { level = Level.NONE; publish(State(status = Status.OFF_ROAD, speedKmh = speed, zone = activeZone())); return }
        val path = Ahead.path(m, loc.latitude, loc.longitude, graph)
        val zones = Ahead.zones(path, cameras)
        // Entering a zone: from then on, it lasts until the car has gone past its end
        zones.firstOrNull { it.start <= 0 && it.end > 0 }?.let { z ->
            if (zoneCam?.id != z.cam.id) {
                zoneCam = z.cam; zoneEnd = odo + z.end; zoneLimit = z.limit
                Log.i(TAG, "zone of camera ${z.cam.id} (${z.cam.kind}, ${z.limit}, ${z.length} m)")
            }
        }
        val zone = activeZone()
        val limit = m.limit
        val p = Prefs(c)
        val t = Thresholds(p.radarLow, p.radarHigh, p.radarPercent, Units.miles(c))
        // In a zone, the speed checked there
        val checked = zone?.limit ?: limit
        level = speed?.let { t.level(it, checked, level) } ?: Level.NONE
        publish(State(
            status = Status.OK, speedKmh = speed, limit = limit, estimated = m.estimated, level = level, zone = zone,
            next = zones.filter { it.start > 0 && it.cam.id != zoneCam?.id }.take(3).map { NextZone(it.cam.kind, it.limit, it.start) },
            nextLimit = Ahead.nextLimit(path), highway = m.road.highway,
        ))
    }

    private fun activeZone(): InZone? {
        val cam = zoneCam ?: return null
        val left = zoneEnd - odo
        if (left <= 0) { zoneCam = null; Log.i(TAG, "out of the zone of camera ${cam.id}"); return null }
        return InZone(cam.kind, zoneLimit, left)
    }

    private fun publish(s: State) {
        if (s != _state.value) _state.value = s
    }

    /** Loads the tiles around the car from disk, fetches those missing or old when allowed, drops those far away. */
    private fun ensureTiles(c: Context, lat: Double, lon: Double, now: Long) {
        val keys = RadarData.keysAround(lat, lon, HALF_LAT, HALF_LON).toSet()
        var changed = false
        for (k in keys) {
            if (k !in tiles) RadarData.read(c.filesDir, k)?.let { tiles[k] = it; changed = true }
        }
        // Far tiles out of memory (kept on disk)
        val keep = RadarData.keysAround(lat, lon, HALF_LAT * 3, HALF_LON * 3).toSet()
        if (tiles.keys.retainAll(keep)) changed = true
        val want = keys.filter { k -> tiles[k].let { it == null || !it.fresh(now) || it.level != RadarData.Level.FULL } && k !in fetching }
        if (want.isNotEmpty() && now - failedAt > BACKOFF_MS && netAllowed(c)) want.forEach { fetch(c, it) }
        if (changed || loadedKeys != tiles.keys) rebuild()
    }

    private fun rebuild() {
        val r = HashMap<Long, Road>(); val cams = HashMap<Long, Camera>()
        tiles.values.forEach { t -> t.roads.forEach { r[it.id] = it }; t.cameras.forEach { cams[it.id] = it } }
        roads = r.values; cameras = cams.values; graph = Ahead.Graph(roads); loadedKeys = tiles.keys.toSet()
    }

    private fun fetch(c: Context, key: RadarData.Key) {
        fetching += key
        net.post {
            val q = RadarData.query(key.south, key.west, key.south + RadarData.TILE, key.west + RadarData.TILE, RadarData.Level.FULL)
            val tile = download(q)?.let { json ->
                runCatching { RadarData.parse(json) }.onFailure { Log.w(TAG, "tile $key: ${it.message}") }.getOrNull()
            }?.let { (r, cams) -> RadarData.Tile(key, RadarData.Level.FULL, System.currentTimeMillis(), r, cams) }
            if (tile != null) runCatching { RadarData.write(c.filesDir, tile) }
            worker.post {
                fetching -= key
                if (tile != null) { tiles[key] = tile; rebuild(); Log.i(TAG, "tile $key: ${tile.roads.size} roads, ${tile.cameras.size} cameras") }
                else failedAt = System.currentTimeMillis()
            }
        }
    }

    /** POSTs [query] to Overpass (the second server if the first fails), counted as the alerter's data. */
    private fun download(query: String): String? = DataUsage.tag(DataUsage.Use.RADARS) {
        for (server in RadarData.SERVERS) {
            try {
                val conn = WifiFirst.bound?.openConnection(URL(server)) as? HttpURLConnection ?: URL(server).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000; conn.readTimeout = 90_000
                conn.requestMethod = "POST"; conn.doOutput = true
                conn.setRequestProperty("User-Agent", "MG4Dash/${BuildConfig.VERSION_NAME}")
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
                val code = conn.responseCode
                if (code == 200) return@tag conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                Log.w(TAG, "$server: HTTP $code")
                conn.disconnect()
            } catch (e: Exception) { Log.w(TAG, "$server: ${e.javaClass.simpleName} ${e.message}") }
        }
        null
    }

    // ── Prepared trips ──

    /**
     * Main roads and cameras within [PREPARE_KM] km of the car, over the Wi-Fi only, in boxes of 0.1°; the tiles
     * already there at the full level are kept. False when it cannot start (no position, no Wi-Fi, running).
     */
    fun prepareTrips(context: Context): Boolean {
        val loc = LocationRepo.location.value ?: return false
        if (!onWifi() || _prepare.value != null) return false
        val c = context.applicationContext
        val dLat = PREPARE_KM * 1000 / Geo.M_PER_LAT; val dLon = PREPARE_KM * 1000 / Geo.mPerLon(loc.latitude)
        val step = 0.1
        val boxes = ArrayList<Pair<Double, Double>>()
        var s = Math.floor((loc.latitude - dLat) / step) * step
        while (s < loc.latitude + dLat) {
            var w = Math.floor((loc.longitude - dLon) / step) * step
            while (w < loc.longitude + dLon) { boxes += s to w; w += step }
            s += step
        }
        _prepare.value = 0 to boxes.size
        Log.i(TAG, "preparing trips: ${boxes.size} boxes")
        net.post {
            var done = 0; var ok = 0
            for ((bs, bw) in boxes) {
                if (!onWifi()) { Log.w(TAG, "preparing stopped: no Wi-Fi"); break }
                val keys = RadarData.keysAround(bs + step / 2, bw + step / 2, step / 2 - 1e-9, step / 2 - 1e-9)
                val now = System.currentTimeMillis()
                val missing = keys.filter { k -> RadarData.read(c.filesDir, k)?.let { !it.fresh(now) } ?: true }
                if (missing.isNotEmpty()) {
                    download(RadarData.query(bs, bw, bs + step, bw + step, RadarData.Level.MAIN))?.let { json -> runCatching { RadarData.parse(json) }.getOrNull() }?.let { (r, cams) ->
                        RadarData.split(missing, RadarData.Level.MAIN, now, r, cams).forEach { runCatching { RadarData.write(c.filesDir, it) } }
                        ok++
                    }
                    // Overpass asks for a pause between big queries
                    Thread.sleep(2_000)
                } else ok++
                done++
                _prepare.value = done to boxes.size
            }
            Log.i(TAG, "preparing trips done: $ok / ${boxes.size}")
            _prepare.value = null
            lastPrepare = ok to boxes.size
        }
        return true
    }

    @Volatile var lastPrepare: Pair<Int, Int>? = null
        private set

    fun clearCache(context: Context) {
        val c = context.applicationContext
        worker.post { tiles.clear(); rebuild(); RadarData.clear(c.filesDir); Log.i(TAG, "cache cleared") }
    }

    fun cacheSize(context: Context): Pair<Int, Long> = RadarData.size(context.filesDir)
}
