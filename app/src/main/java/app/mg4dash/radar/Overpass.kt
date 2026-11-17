package app.mg4dash.radar

/**
 * The Overpass servers the alerter reads OpenStreetMap from, and how it goes through them.
 *
 * Android 9 has no TLS 1.3: overpass-api.de now only accepts TLS 1.3 (seen on the car on 17/11: every request
 * "SSLHandshakeException Handshake failed"), so it is tried last, for newer head units. The others accept TLS 1.2.
 * maps.mail.ru first: it compresses its answers (about a fifth of the data, measured 17/11). The server that answered
 * last is tried first next time.
 */
object Overpass {
    val SERVERS = listOf(
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://overpass-api.de/api/interpreter",
    )

    /** Index of the server that answered last. */
    @Volatile var preferred = 0

    /** The servers in the order to try them: the last one that answered first. */
    fun order(first: Int = preferred): List<String> = SERVERS.indices.map { SERVERS[Math.floorMod(first + it, SERVERS.size)] }

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
