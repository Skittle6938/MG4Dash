package app.mg4dash.radar

/**
 * The Overpass servers the alerter reads OpenStreetMap from, and how it goes through them (Marc's choice, 17/11).
 *
 * The official server, overpass-api.de (Germany / Finland), always first. It only accepts TLS 1.3, which Android 9
 * lacks: [RadarHttp] brings it with Conscrypt. Then its second official address, z.overpass-api.de (Marc, 18/11: the
 * first one answered "at capacity" (502) to almost every request while z gave whole tiles in 20 to 40 s), then one
 * public instance as a last fallback, overpass.private.coffee (Austria; overpass.kumi.systems is the same server),
 * left out for a while when it does not answer. All send gzip when asked.
 */
object Overpass {
    val SERVERS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://z.overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )

    /**
     * An answer with HTTP 200 that is not data: Overpass reports a query stopped half-way (timeout, out of memory) in
     * "remark" with the elements found so far, or none (wiki: Overpass API, "runtime error"). Kept, it would hold a
     * tile with roads missing until its refresh; it is a failed try instead.
     */
    fun runtimeError(json: String): String? =
        Regex("\"remark\"\\s*:\\s*\"([^\"]*)\"").find(json.take(4096) + json.takeLast(4096))?.groupValues?.get(1)
            ?.takeIf { it.contains("error", ignoreCase = true) }

    /**
     * The servers in the order to try them: the official one first, always; a fallback that gave no answer twice in a
     * row (a timeout, no connection: 90 s lost each round, Marc, 17/11) is left out for [SKIP_MS].
     */
    fun order(now: Long = System.currentTimeMillis()): List<String> {
        val open = SERVERS.filterIndexed { i, s -> i == 0 || (skippedUntil[s] ?: 0L) <= now }
        // The last server that gave data first (Marc, 18/11): no time lost again on a full one at each request
        val p = preferred?.takeIf { it in open } ?: return open
        return listOf(p) + (open - p)
    }

    /** The last server that gave data, tried first; null: the list's order. */
    @Volatile var preferred: String? = null
        private set

    /** [server] gave data: it is tried first from now on. */
    fun worked(server: String) { preferred = server }

    /** No answer in a row from a fallback before it is left out. */
    const val SKIP_AFTER = 2
    const val SKIP_MS = 30L * 60_000L
    private val noAnswer = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val skippedUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** [server] answered (whatever its HTTP code): it counts again. */
    fun answered(server: String) { noAnswer.remove(server); skippedUntil.remove(server) }

    /** [server] gave no answer (a timeout, a failed connection): left out for a while after [SKIP_AFTER] in a row. */
    fun noAnswer(server: String, now: Long = System.currentTimeMillis()) {
        val n = (noAnswer[server] ?: 0) + 1
        noAnswer[server] = n
        if (n >= SKIP_AFTER && server != SERVERS.first()) { skippedUntil[server] = now + SKIP_MS; noAnswer.remove(server) }
    }

    internal fun resetSkips() { noAnswer.clear(); skippedUntil.clear(); preferred = null }

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
