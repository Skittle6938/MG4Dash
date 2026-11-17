package app.mg4dash.radar

/**
 * The Overpass servers the alerter reads OpenStreetMap from, and how it goes through them (Marc's choice, 17/11).
 *
 * The official server, overpass-api.de (Germany / Finland), always first. It only accepts TLS 1.3, which Android 9
 * lacks: [RadarHttp] brings it with Conscrypt. Then one public instance as a fallback, overpass.private.coffee
 * (Austria; overpass.kumi.systems is the same server). Both send gzip when asked.
 */
object Overpass {
    val SERVERS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )

    /** The servers in the order to try them: the official one first, always. */
    fun order(): List<String> = SERVERS

    /** A server answer that says it is busy: try another one, and wait longer before the next round. */
    fun busy(code: Int) = code == 429 || code == 503 || code == 504

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
    class Result(val body: String?, val busy: Boolean = false, val error: String? = null)
}
