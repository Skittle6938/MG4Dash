package app.mg4dash.radar

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import app.mg4dash.BuildConfig
import app.mg4dash.car.CarBridge
import app.mg4dash.car.CarProps
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
    /** Prepared trips: radii offered around the car (km), 20 by default, 50 at most (17/11). */
    val PREPARE_RADII = listOf(10, 20, 30, 50)
    const val PREPARE_DEFAULT_KM = 20

    /** A radius stored before (80 km) or out of the list: the nearest one offered, at most the largest. */
    fun prepareRadius(stored: Int): Int = PREPARE_RADII.lastOrNull { it <= stored } ?: PREPARE_RADII.first()
    /**
     * Typical size of one tile (0.02°) with all its streets, for a mix of countryside, suburbs and city (measured on 17/11:
     * about 9 kB, 330 kB and 650 kB uncompressed); the official server compresses it to about a fifth.
     */
    const val PREP_BOX_BYTES = 250_000L
    /** Downloaded (gzip), and kept on disk in the compact form. */
    const val PREP_BOX_BYTES_GZIP = 50_000L
    const val PREP_BOX_BYTES_KEPT = 140_000L
    /** Typical time per query (2 × 2 tiles): the server's answer and the pause between queries. */
    const val PREP_BOX_SECONDS = 15

    /** Estimate of a preparation: boxes, bytes downloaded (compressed), bytes kept, minutes. */
    data class Estimate(val boxes: Int, val download: Long, val kept: Long, val minutes: Int)

    enum class Status { OK, NO_POSITION, LOADING, NO_DATA, WIFI_ONLY, OFF_ROAD }

    /** The zone the car is in: what checks it, the speed checked, metres left to its end. */
    class InZone(val kind: CamKind, val limit: Int?, val toEnd: Double, val cam: Long = 0)

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
        /** The limit is the car's own (ISA), OSM having none. */
        val fromCar: Boolean = false,
    ) {
        /** Over the high threshold inside a camera zone: the very strong alert. */
        val critical: Boolean get() = zone != null && level == Level.STRONG
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** Prepared trips: where the preparation is, or how the last one ended (null: none since the start). */
    enum class PrepPhase { RUNNING, WAIT_WIFI, RETRY, PAUSED, DONE, NO_WIFI, CANCELLED }
    /** [bytes]: received so far; [minutesLeft]: from the pace so far, null at the start. */
    /** [busyUntil]: the servers are saturated, next try at this time (ms), 0 otherwise. */
    data class Prep(val done: Int, val total: Int, val failed: Int, val phase: PrepPhase, val bytes: Long = 0, val minutesLeft: Int? = null, val busyUntil: Long = 0) {
        val running: Boolean get() = phase == PrepPhase.RUNNING || phase == PrepPhase.WAIT_WIFI || phase == PrepPhase.RETRY || phase == PrepPhase.PAUSED

        /**
         * Seconds before the next try while the servers are saturated, counted from the worker's absolute time, so
         * that whoever shows it can count down every second; null when not waiting for them.
         */
        fun retryLeftS(now: Long): Long? = if (phase == PrepPhase.RETRY && busyUntil > 0) maxOf(1L, (busyUntil - now + 999) / 1000) else null
    }

    /** Pause, resume and cancel of the preparation (from its page). */
    @Volatile private var prepPaused = false
    @Volatile private var prepCancelled = false
    fun pausePrepare(on: Boolean) { prepPaused = on }
    fun cancelPrepare() { prepCancelled = true; prepPaused = false }
    private val _prepare = MutableStateFlow<Prep?>(null)
    val prepare: StateFlow<Prep?> = _prepare
    private val prep: Handler by lazy { Handler(HandlerThread("radar-prepare").apply { start() }.looper) }

    private var ctx: Context? = null
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val worker: Handler by lazy { Handler(HandlerThread("radar").apply { start() }.looper) }
    private val net: Handler by lazy { Handler(HandlerThread("radar-net").apply { start() }.looper) }

    // Worker thread state
    private val tiles = HashMap<RadarData.Key, RadarData.Tile>()
    private val fetching = HashSet<RadarData.Key>()
    /** Tiles being downloaded around the car now (live), for the card's download mark. */
    private val _live = MutableStateFlow(0)
    val live: StateFlow<Int> = _live
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
        resumePrepare(ctx!!)
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
    /** The Wi-Fi in use (internet checked), else the Wi-Fi connected, null without Wi-Fi. */
    private fun wifi(): android.net.Network? = WifiFirst.bound ?: WifiFirst.wifi
    private fun onWifi(): Boolean = wifi() != null

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
        val p = Prefs(c)
        // OSM's limit; the car's own (ISA) where OSM has none, once its property is known
        val carLimit = if (m.tagged == null && p.radarIsaFallback) Isa.carLimit() else null
        val limit = carLimit ?: m.limit
        if (p.radarIsaLog) IsaLog.sample(c, now, m.tagged, speed)
        if (p.radarIsaWrite) IsaWrite.apply(m.tagged, m.tagged == null, Isa.carLimit(), now, false, hasIsa = false) { w ->
            w.f?.let { CarProps.setFloat(w.id, it, "navigation speed limit") } ?: CarProps.setInt(w.id, w.i ?: 0, "navigation speed limit") }
        val t = Thresholds(p.radarLow, p.radarHigh, p.radarPercent, Units.miles(c))
        // In a zone, the speed checked there
        val checked = zone?.limit ?: limit
        level = speed?.let { t.level(it, checked, level) } ?: Level.NONE
        publish(State(
            status = Status.OK, speedKmh = speed, limit = limit, estimated = carLimit == null && m.estimated, level = level, zone = zone, fromCar = carLimit != null,
            next = zones.filter { it.start > 0 && it.cam.id != zoneCam?.id }.take(3).map { NextZone(it.cam.kind, it.limit, it.start) },
            nextLimit = Ahead.nextLimit(path), highway = m.road.highway,
        ))
    }

    private fun activeZone(): InZone? {
        val cam = zoneCam ?: return null
        val left = zoneEnd - odo
        if (left <= 0) { zoneCam = null; Log.i(TAG, "out of the zone of camera ${cam.id}"); return null }
        return InZone(cam.kind, zoneLimit, left, cam.id)
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
        val refreshMs = Prefs(c).radarRefreshHours * 3_600_000L
        val want = keys.filter { k -> needsFetch(tiles[k], now, refreshMs) && k !in fetching }
        // At most one tile started every few seconds: driving through many tiles does not hammer the server
        if (want.isNotEmpty() && now - failedAt > BACKOFF_MS && now - lastFetch >= FETCH_GAP_MS && netAllowed(c)) {
            lastFetch = now
            fetch(c, want.first())
        }
        if (changed || loadedKeys != tiles.keys) rebuild()
    }

    /** Live downloads: one tile started at most every this long. */
    private const val FETCH_GAP_MS = 5_000L
    private var lastFetch = 0L

    /** Refresh ages offered for the tiles around the car (hours), 6 by default (17/11). */
    val REFRESH_HOURS = listOf(1, 2, 6, 24, 168, 720)
    const val REFRESH_DEFAULT_HOURS = 6

    /**
     * The tile under or around the car is downloaded again: missing, of main roads only, or older than [refreshMs] (the
     * live refresh age; prepared tiles elsewhere keep their 30 days). The old tile stays in use until the new one is in.
     */
    internal fun needsFetch(t: RadarData.Tile?, now: Long, refreshMs: Long): Boolean =
        t == null || t.level != RadarData.Level.FULL || now - t.fetched >= refreshMs || !t.fresh(now)

    private fun rebuild() {
        val r = HashMap<Long, Road>(); val cams = HashMap<Long, Camera>()
        tiles.values.forEach { t -> t.roads.forEach { r[it.id] = it }; t.cameras.forEach { cams[it.id] = it } }
        roads = r.values; cameras = cams.values; graph = Ahead.Graph(roads); loadedKeys = tiles.keys.toSet()
    }

    private fun fetch(c: Context, key: RadarData.Key) {
        fetching += key
        _live.value = fetching.size
        net.post {
            val q = RadarData.query(key.south, key.west, key.south + RadarData.TILE, key.west + RadarData.TILE, RadarData.Level.FULL)
            val tile = download(q, Prefs(c).radarWifiOnly).body?.let { json ->
                runCatching { RadarData.parse(json) }.onFailure { Log.w(TAG, "tile $key: ${it.message}") }.getOrNull()
            }?.let { (r, cams) -> RadarData.Tile(key, RadarData.Level.FULL, System.currentTimeMillis(), r, cams) }
            if (tile != null) runCatching { RadarData.write(c.filesDir, tile) }
            worker.post {
                fetching -= key
                _live.value = fetching.size
                if (tile != null) { tiles[key] = tile; rebuild(); Log.i(TAG, "tile $key: ${tile.roads.size} roads, ${tile.cameras.size} cameras") }
                else failedAt = System.currentTimeMillis()
            }
        }
    }

    /**
     * POSTs [query] to Overpass, on the Wi-Fi first when there is one, then on the 4G unless [wifiOnly]; each network
     * through the servers in turn (a busy one, HTTP 429 / 503 / 504, gives way to the next). Counted as the alerter's
     * data, on the network it went through.
     */
    private fun download(query: String, wifiOnly: Boolean): Overpass.Result = DataUsage.tag(DataUsage.Use.RADARS) {
        var busy = false; var error: String? = null; var retryAfter = 0
        val body = ("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()
        val agent = "MG4Dash/${BuildConfig.VERSION_NAME}"
        for (route in Overpass.routes(wifi(), wifiOnly)) {
            val via = if (route != null) "Wi-Fi" else "default network"
            for (server in Overpass.order()) {
                var wire = 0
                try {
                    val url = URL(server)
                    val (code, text) = if (RadarHttp.tls13) {
                        // TLS 1.3 (Conscrypt): the official server needs it
                        // Official server: a free slot first (its status page), rather than a refused query
                        if (server == Overpass.SERVERS.first()) {
                            val st = try { RadarHttp.get(URL(Overpass.STATUS), agent, route, 15_000, 20_000) } catch (_: Exception) { null }
                            if (st != null && Overpass.busy(st.code)) { busy = true; error = "HTTP ${st.code}"; Log.w(TAG, "$server ($via): status ${st.code}, busy"); continue }
                            val waitS = st?.takeIf { it.code == 200 }?.let { Overpass.slotWait(it.body.toString(Charsets.UTF_8)) } ?: 0
                            if (waitS in 1..120) { Log.i(TAG, "$server: next slot in $waitS s"); Thread.sleep(waitS * 1000L + 500) }
                            else if (waitS > 120) { busy = true; retryAfter = maxOf(retryAfter, waitS); continue }
                        }
                        val r = RadarHttp.post(url, body, "application/x-www-form-urlencoded", agent, route, 15_000, 90_000)
                        wire = r.wire
                        r.headers["retry-after"]?.trim()?.toIntOrNull()?.let { retryAfter = maxOf(retryAfter, it) }
                        r.code to (if (r.code == 200) r.body.toString(Charsets.UTF_8) else null)
                    } else {
                        // Android's own TLS (1.2): the official server refuses it, the fallback answers
                        val conn = (route?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
                        conn.connectTimeout = 15_000; conn.readTimeout = 90_000
                        conn.requestMethod = "POST"; conn.doOutput = true
                        conn.setRequestProperty("User-Agent", agent)
                        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                        conn.setRequestProperty("Accept-Encoding", "gzip")
                        conn.outputStream.use { it.write(body) }
                        val c = conn.responseCode
                        val t = if (c == 200) {
                            val raw = conn.inputStream.use { it.readBytes() }
                            wire = raw.size
                            (if (conn.contentEncoding.equals("gzip", ignoreCase = true)) java.util.zip.GZIPInputStream(raw.inputStream()).use { it.readBytes() } else raw).toString(Charsets.UTF_8)
                        } else null
                        conn.disconnect()
                        c to t
                    }
                    if (code == 200 && text != null) return@tag Overpass.Result(text, wire = wire)
                    if (Overpass.busy(code)) busy = true
                    error = "HTTP $code"
                    Log.w(TAG, "$server ($via): HTTP $code")
                } catch (e: Exception) {
                    error = e.javaClass.simpleName
                    Log.w(TAG, "$server ($via): ${e.javaClass.simpleName} ${e.message}")
                }
            }
        }
        Overpass.Result(null, busy, error, retryAfter = retryAfter)
    }

    // ── Prepared trips ──

    /**
     * The preparation asks for boxes of [PREP_TILES] × [PREP_TILES] tiles of the disk grid (0.04°), lined up on the tiles:
     * fewer, larger queries, as Overpass prefers (its slots are per query), each split and kept tile by tile as soon as it
     * is in, so that a preparation stopped and started again goes on from there (tiles already in and fresh are skipped).
     */
    internal const val PREP_TILES = 2
    /** Tries per tile, and how long the Wi-Fi may be gone before the preparation stops. */
    private const val PREP_TRIES = 4
    private const val PREP_WIFI_WAIT_MS = 600_000L


    /** The boxes covering [km] km around [lat], [lon]: the south-west tile of each, on the grid of [PREP_TILES]. */
    internal fun prepareBoxes(lat: Double, lon: Double, km: Int): List<RadarData.Key> {
        val dLat = km * 1000 / Geo.M_PER_LAT; val dLon = km * 1000 / Geo.mPerLon(lat)
        val a = RadarData.Key.of(lat - dLat, lon - dLon); val b = RadarData.Key.of(lat + dLat, lon + dLon)
        val n = PREP_TILES
        val boxes = ArrayList<RadarData.Key>()
        for (y in Math.floorDiv(a.y, n)..Math.floorDiv(b.y, n)) for (x in Math.floorDiv(a.x, n)..Math.floorDiv(b.x, n)) boxes += RadarData.Key(y * n, x * n)
        return boxes
    }

    /** The tiles of the box whose south-west tile is [sw]. */
    internal fun boxTiles(sw: RadarData.Key): List<RadarData.Key> =
        (0 until PREP_TILES).flatMap { dy -> (0 until PREP_TILES).map { dx -> RadarData.Key(sw.y + dy, sw.x + dx) } }

    /** Estimate for a radius (at [lat]; 48.8 when the position is unknown), for a mix of countryside, suburbs and city. */
    fun prepareEstimate(km: Int, lat: Double = LocationRepo.location.value?.latitude ?: 48.8): Estimate {
        val n = prepareBoxes(lat, 0.025, km).size
        val tiles = n * PREP_TILES * PREP_TILES
        return Estimate(tiles, tiles * PREP_BOX_BYTES_GZIP, tiles * PREP_BOX_BYTES_KEPT, Math.max(1, (n * PREP_BOX_SECONDS + 59) / 60))
    }

    /**
     * All the roads and cameras within the radius chosen (Prefs.radarPrepareKm) around the car, over the Wi-Fi only, in
     * tiles ([PREP_TILES]); tiles already there and fresh are kept, the others written over. A tile that fails is tried
     * again after a pause (longer when the servers are busy); without Wi-Fi it waits for it up to 10 min, then stops. False when it cannot start (no position, no
     * Wi-Fi, already running).
     */
    fun prepareTrips(context: Context): Boolean {
        val loc = LocationRepo.location.value ?: return false
        if (!onWifi() || _prepare.value?.running == true) return false
        return prepareAround(context, loc.latitude, loc.longitude)
    }

    /** Where an unfinished preparation was centred ("lat,lon"): taken up again at the next start (an update, a restart). */
    private const val PREP_RESUME_KEY = "radar_prep_resume"

    /**
     * A preparation cut by the app stopping (an update, the car switched off) goes on at the next start, around the
     * same place: the tiles already in and fresh are kept, so it starts again where it was.
     */
    private fun resumePrepare(context: Context) {
        val at = Prefs(context).raw.getString(PREP_RESUME_KEY, null)?.split(',')?.mapNotNull { it.toDoubleOrNull() } ?: return
        if (at.size != 2 || _prepare.value?.running == true) return
        Log.i(TAG, "preparing trips: taken up again")
        prepareAround(context, at[0], at[1])
    }

    private fun prepareAround(context: Context, lat: Double, lon: Double): Boolean {
        val c = context.applicationContext
        val resume = Prefs(c).raw
        resume.edit().putString(PREP_RESUME_KEY, "$lat,$lon").apply()
        // The complete data, all the streets, as around the car while driving (17/11)
        val level = RadarData.Level.FULL
        val boxes = prepareBoxes(lat, lon, prepareRadius(Prefs(c).radarPrepareKm))
        // Progress in tiles (each block is split again if the server cannot answer it whole)
        val total = boxes.size * PREP_TILES * PREP_TILES
        _prepare.value = Prep(0, total, 0, PrepPhase.RUNNING)
        Log.i(TAG, "preparing trips: ${boxes.size} blocks, $total tiles")
        prep.post {
            var done = 0; var failed = 0
            var bytes = 0L
            val started = System.currentTimeMillis()
            prepPaused = false; prepCancelled = false
            fun left(): Int? = if (done < 8) null else (((System.currentTimeMillis() - started) / done) * (total - done) / 60_000L).toInt()
            var retryAt = 0L
            fun show(phase: PrepPhase) { _prepare.value = Prep(done, total, failed, phase, bytes, left(), if (phase == PrepPhase.RETRY) retryAt else 0L) }
            // Waits for a Wi-Fi up to PREP_WIFI_WAIT_MS: false if none came back
            fun waitWifi(): Boolean {
                if (onWifi()) return true
                show(PrepPhase.WAIT_WIFI)
                val until = System.currentTimeMillis() + PREP_WIFI_WAIT_MS
                while (System.currentTimeMillis() < until && !prepCancelled) { Thread.sleep(1_000); if (onWifi()) { show(PrepPhase.RUNNING); return true } }
                if (prepCancelled) return true   // the cancel is told by the loop
                return false
            }
            fun cancelled(): Boolean {
                if (!prepCancelled) return false
                Log.i(TAG, "preparing cancelled ($done / $total)"); resume.edit().remove(PREP_RESUME_KEY).apply(); show(PrepPhase.CANCELLED); return true
            }
            // Blocks to fetch: the 2 x 2 ones first; one that fails or is too big comes back as its quarters
            val queue = ArrayDeque(boxes.map { it to PREP_TILES })
            while (queue.isNotEmpty()) {
                // Paused: waits; cancelled: stops, the tiles already in are kept
                while (prepPaused && !prepCancelled) { show(PrepPhase.PAUSED); Thread.sleep(1_000) }
                if (cancelled()) return@post
                val (sw, size) = queue.removeFirst()
                val keys = blockTiles(sw, size)
                val now = System.currentTimeMillis()
                // A tile of main roads only (earlier preparations) is fetched again with all its streets
                val missing = keys.filter { k -> RadarData.read(c.filesDir, k)?.let { !it.fresh(now) || it.level != RadarData.Level.FULL } ?: true }
                if (missing.isEmpty()) { done += keys.size; show(PrepPhase.RUNNING); continue }
                val bn = RadarData.Key(sw.y + size, sw.x).south; val be = RadarData.Key(sw.y, sw.x + size).west
                var ok = false; var split = false
                // Failed tries: up to PREP_TRIES for a tile, 2 for a block (then its quarters); busy servers do not count
                var attempt = 0; var busyStreak = 0; var lastRetryAfter = 0
                while (true) {
                    if (!waitWifi()) {
                        Log.w(TAG, "preparing stopped: no Wi-Fi for ${PREP_WIFI_WAIT_MS / 1000} s ($done / $total)")
                        show(PrepPhase.NO_WIFI); return@post
                    }
                    if (cancelled()) return@post
                    if (attempt > 0 || busyStreak > 0) {
                        val wait = if (busyStreak > 0) maxOf(Overpass.busyWaitMs(busyStreak), lastRetryAfter * 1000L) else Overpass.backoffMs(attempt + 1, false)
                        retryAt = if (busyStreak > 0) System.currentTimeMillis() + wait else 0L
                        show(PrepPhase.RETRY)
                        val until = System.currentTimeMillis() + wait
                        while (System.currentTimeMillis() < until && !prepCancelled) Thread.sleep(1_000)
                        if (cancelled()) return@post
                        show(PrepPhase.RUNNING)
                    }
                    val r = download(RadarData.query(sw.south, sw.west, bn, be, level), wifiOnly = true)
                    bytes += r.wire
                    if (size > 1 && (r.body?.length ?: 0) > PREP_MAX_ANSWER) { split = true; Log.i(TAG, "preparing: block $sw too big (${r.body?.length}), in quarters"); break }
                    val parsed = r.body?.let { json -> runCatching { RadarData.parse(json) }.getOrNull() }
                    if (parsed != null) {
                        RadarData.split(missing, level, now, parsed.first, parsed.second).forEach { runCatching { RadarData.write(c.filesDir, it) } }
                        ok = true; break
                    }
                    if (r.busy) { busyStreak++; lastRetryAfter = r.retryAfter; Log.w(TAG, "preparing: block $sw, servers busy ($busyStreak), waiting"); continue }
                    busyStreak = 0; attempt++
                    Log.w(TAG, "preparing: block $sw ($size x $size) failed (try $attempt, ${r.error})")
                    if (size > 1 && attempt >= 2) { split = true; break }
                    if (attempt >= PREP_TRIES) break
                }
                if (split) { subBlocks(sw, size).reversed().forEach { queue.addFirst(it) }; continue }
                if (ok) done += keys.size else { failed += missing.size; done += keys.size }
                show(PrepPhase.RUNNING)
                // A short pause between queries, as Overpass asks
                Thread.sleep(1_000)
            }
            Log.i(TAG, "preparing trips done: ${done - failed} / $total tiles, $failed failed")
            resume.edit().remove(PREP_RESUME_KEY).apply()
            show(PrepPhase.DONE)
        }
        return true
    }

    /** An answer larger than this (characters) for a block: asked again as its quarters. */
    internal const val PREP_MAX_ANSWER = 8_000_000

    /** The tiles of a block of [size] × [size] tiles whose south-west tile is [sw]. */
    internal fun blockTiles(sw: RadarData.Key, size: Int): List<RadarData.Key> =
        (0 until size).flatMap { dy -> (0 until size).map { dx -> RadarData.Key(sw.y + dy, sw.x + dx) } }

    /** The quarters of a block (size halved), south-west first; a single tile has none. */
    internal fun subBlocks(sw: RadarData.Key, size: Int): List<Pair<RadarData.Key, Int>> {
        if (size <= 1) return emptyList()
        val h = size / 2
        return listOf(0 to 0, 0 to h, h to 0, h to h).map { (dy, dx) -> RadarData.Key(sw.y + dy, sw.x + dx) to h }
    }

    fun clearCache(context: Context) {
        val c = context.applicationContext
        worker.post { tiles.clear(); rebuild(); RadarData.clear(c.filesDir); Log.i(TAG, "cache cleared") }
    }

    fun cacheSize(context: Context): Pair<Int, Long> = RadarData.size(context.filesDir)
}
