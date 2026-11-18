package app.mg4dash.radar

/**
 * The Overpass servers the alerter reads OpenStreetMap from, and how it goes through them.
 *
 * Measured on 18/11 (a dense tile of all the streets, three tries each): the overpass-api.de servers take 35 to 45 s
 * a request whatever its size (waiting for a slot, not the data), and refuse about 40 % ("at capacity", 502 / 504);
 * overpass.private.coffee no longer answers. ovp.dresel.systems (listed on the OSM wiki: free, no key, up to 4
 * queries at a time per address, updated every night) gave the same data in 7 s, and 3 x 3 tiles in 14 s, every time.
 * So by the region of the area asked: dresel's European or North American server first, then the official ones
 * (z, lz4, the main address; first elsewhere). Never maps.mail.ru (the owner's choice). The official servers need TLS 1.3,
 * which Android 9 lacks: [RadarHttp] brings it with Conscrypt.
 */
object Overpass {
    const val DRESEL_EU = "https://ovp.dresel.systems/api/interpreter"
    const val DRESEL_NA = "https://ovp.dresel.systems/api-na/interpreter"
    val OFFICIAL = listOf(
        "https://z.overpass-api.de/api/interpreter",
        "https://lz4.overpass-api.de/api/interpreter",
        "https://overpass-api.de/api/interpreter",
    )

    enum class Region { EUROPE, NORTH_AMERICA, OTHER }

    /** The region of a point, for the servers that cover it (dresel's extracts: Europe, North America). */
    fun region(lat: Double, lon: Double): Region = when {
        lat in 34.0..72.0 && lon in -25.0..45.0 -> Region.EUROPE
        lat in 14.0..72.0 && lon in -170.0..-50.0 -> Region.NORTH_AMERICA
        else -> Region.OTHER
    }

    /** The servers for [region], in their own order. */
    fun servers(region: Region): List<String> = when (region) {
        Region.EUROPE -> listOf(DRESEL_EU) + OFFICIAL
        Region.NORTH_AMERICA -> listOf(DRESEL_NA) + OFFICIAL
        Region.OTHER -> OFFICIAL
    }

    /** Every server MG4Dash may ask. */
    val SERVERS: List<String> get() = (listOf(DRESEL_EU, DRESEL_NA) + OFFICIAL)

    /** The Identification Overpass asks for, without any personal data. */
    val USER_AGENT: String get() = "MG4Dash/${app.mg4dash.BuildConfig.VERSION_NAME} (+https://github.com/Skittle6938/MG4Dash)"

    /** Waiting for the answer: dresel answers within seconds, the official servers make the query wait its turn. */
    fun readTimeoutMs(server: String): Int = if (server.contains("dresel")) 45_000 else 90_000

    /** The [timeout:] the query declares to [server], under its socket wait. */
    fun queryTimeoutS(server: String): Int = if (server.contains("dresel")) 40 else 85

    /**
     * The servers for [region] in the order to try them: the last one that gave data there first; one left out (no
     * answer twice in a row, or asked to pause) skipped until its time, unless all are.
     */
    fun order(region: Region, now: Long = System.currentTimeMillis()): List<String> {
        val all = servers(region)
        val open = all.filter { (skippedUntil[it] ?: 0L) <= now }.ifEmpty { all }
        val p = preferred[region]?.takeIf { it in open } ?: return open
        return listOf(p) + (open - p)
    }

    private val preferred = java.util.concurrent.ConcurrentHashMap<Region, String>()

    /** The last server that gave data for [region], tried first there; null: the list's order. */
    fun preferred(region: Region): String? = preferred[region]

    /** [server] gave data for [region]: it is tried first there from now on. */
    fun worked(region: Region, server: String) { preferred[region] = server }

    /** No answer in a row before a server is left out for [SKIP_MS]. */
    const val SKIP_AFTER = 2
    const val SKIP_MS = 30L * 60_000L
    /** HTTP 429 / 406 (too many requests): that server paused this long, as its usage policy asks. */
    const val PAUSE_MS = 30_000L
    private val noAnswer = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val skippedUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** [server] answered (whatever its HTTP code): it counts again. */
    fun answered(server: String) { noAnswer.remove(server); if ((skippedUntil[server] ?: 0L) - System.currentTimeMillis() > PAUSE_MS) skippedUntil.remove(server) }

    /** [server] gave no answer (a timeout, a failed connection): left out for a while after [SKIP_AFTER] in a row. */
    fun noAnswer(server: String, now: Long = System.currentTimeMillis()) {
        val n = (noAnswer[server] ?: 0) + 1
        noAnswer[server] = n
        if (n >= SKIP_AFTER) { skippedUntil[server] = now + SKIP_MS; noAnswer.remove(server) }
    }

    /** [server] asked to slow down (HTTP 429 / 406): paused [PAUSE_MS]. */
    fun pause(server: String, now: Long = System.currentTimeMillis()) { skippedUntil[server] = maxOf(skippedUntil[server] ?: 0L, now + PAUSE_MS) }

    /** What to do with an HTTP [code] other than 200: true = try the next server (busy), and pause it on 429 / 406. */
    fun tooMany(code: Int) = code == 429 || code == 406

    internal fun resetSkips() { noAnswer.clear(); skippedUntil.clear(); preferred.clear() }

    /**
     * An answer with HTTP 200 that is not data: Overpass reports a query stopped half-way (timeout, out of memory) in
     * "remark" with the elements found so far, or none (wiki: Overpass API, "runtime error"). Kept, it would hold a
     * tile with roads missing until its refresh; it is a failed try instead.
     */
    fun runtimeError(json: String): String? =
        Regex("\"remark\"\\s*:\\s*\"([^\"]*)\"").find(json.take(4096) + json.takeLast(4096))?.groupValues?.get(1)
            ?.takeIf { it.contains("error", ignoreCase = true) }

    /** A server answer that says it is busy: try another one, and wait longer before the next round. */
    fun busy(code: Int) = code == 429 || code == 502 || code == 503 || code == 504

    /**
     * Wait after the [n]th busy answer in a row (overpass-api.de: "at capacity, retry using exponential backoff"): 30 s
     * doubling, at most 5 min. Busy answers are not failed tries: the same request waits its turn.
     */
    fun busyWaitMs(n: Int): Long = minOf(30_000L * (1L shl (n - 1).coerceIn(0, 4)), 300_000L)

    /** Pause before attempt [attempt] (2, 3…) of the same request: 5 s doubling, 30 s doubling when the servers were busy. */
    fun backoffMs(attempt: Int, busy: Boolean): Long = (if (busy) 30_000L else 5_000L) * (1L shl (attempt - 2).coerceIn(0, 3))

    /**
     * The networks to try, in order: the Wi-Fi first when there is one; then Android's default network (the car's 4G)
     * unless the request is for the Wi-Fi only. Null stands for the default network.
     */
    fun <N> routes(wifi: N?, wifiOnly: Boolean): List<N?> = buildList {
        if (wifi != null) add(wifi)
        if (!wifiOnly) add(null)
    }

    /** Outcome of a request: the answer, or whether the servers were busy and the last error. */
    /** [retryAfter]: seconds the server asked to wait (Retry-After), 0 when it did not say. */
    class Result(val body: String?, val busy: Boolean = false, val error: String? = null, val wire: Int = 0, val retryAfter: Int = 0)

    const val STATUS = "https://overpass-api.de/api/status"

    /**
     * Wait before the next query from the official server's status page (seconds): 0 with a slot free now, the time to
     * the next slot otherwise; null when the page says nothing usable (asked anyway).
     */
    fun slotWait(status: String): Int? {
        Regex("(\\d+) slots? available now").find(status)?.let { if (it.groupValues[1].toInt() > 0) return 0 }
        val waits = Regex("in (-?\\d+) seconds").findAll(status).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
        return if (waits.isEmpty()) null else maxOf(0, waits.min())
    }
}
