package app.mg4dash.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.mg4dash.cards.LyricsCardPrefs
import app.mg4dash.system.DataUsage
import app.mg4dash.system.Download
import app.mg4dash.system.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** LRCLIB lookup and size bounded LRU cache. All public disk/network operations run on IO. */
object LyricsRepo {
    data class Track(val key: String, val artist: String, val title: String, val synced: List<Pair<Long, String>>?, val plain: String?, val instrumental: Boolean = false,
                     /** LRCLIB's id of these lyrics (0: unknown). */
                     val id: Long = 0)
    /** One of LRCLIB's results for a song, for the picker: its own lyrics in [json]. */
    data class Found(val id: Long, val title: String, val artist: String, val album: String, val durationMs: Long,
                     val synced: Boolean, val instrumental: Boolean, val json: JSONObject)
    data class CacheInfo(val bytes: Long, val tracks: Int)
    private class HttpResponse(val code: Int, val body: String)

    private const val BASE = "https://lrclib.net/api"
    @Volatile private var app: Context? = null
    private val memory = LinkedHashMap<String, Track>()
    private val activeConnections = java.util.concurrent.ConcurrentHashMap.newKeySet<HttpURLConnection>()
    fun start(context: Context) {
        val first = app == null
        app = context.applicationContext; LyricsCardPrefs(context); LyricsChoices.start(context)
        if (first) watchWifi(context.applicationContext)
    }

    /** A lyrics request on the network right now (#142 log). */
    val busy: Boolean get() = activeConnections.isNotEmpty()

    fun cancelActiveRequest() {
        activeConnections.toList().forEach { connection -> try { connection.disconnect() } catch (_: Exception) { } }
    }

    /**
     * A radio (FM, AM, DAB): its title is the station's name, not a song's, so no lyrics are looked up for it
     * (25/11). The car's radio app, or a title that only repeats the source's name.
     */
    fun radio(m: MediaHub.NowPlaying): Boolean = m.analog || m.packageName?.substringBefore('#') == RADIO_PKG ||
        (m.source.isNotBlank() && m.title.trim().equals(m.source.trim(), ignoreCase = true))

    /** Lyrics can be looked up for [m]: a song with an artist and a title, not a radio. */
    fun searchable(m: MediaHub.NowPlaying): Boolean = m.available && !radio(m) && m.artist.isNotBlank() && m.title.isNotBlank()

    private const val RADIO_PKG = "com.saicmotor.radio"

    fun key(artist: String, title: String) = "${artist.trim().lowercase(Locale.ROOT)}\u0000${title.trim().lowercase(Locale.ROOT)}"

    fun wifiConnected(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = if (Build.VERSION.SDK_INT >= 23) cm.getNetworkCapabilities(network) else null
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } catch (_: Exception) { false }

    /** Shared network policy for every lyrics client, including the background overlay. */
    fun networkAllowed(context: Context): Boolean = !LyricsCardPrefs(context).wifiOnly || wifiConnected(context)

    suspend fun cached(artist: String, title: String): Track? = withContext(Dispatchers.IO) {
        val k = key(artist, title)
        val ctx = app ?: return@withContext null
        if (!LyricsCardPrefs(ctx).cacheEnabled) return@withContext null
        val f = file(ctx, k)
        val inMemory = synchronized(memory) { memory[k] }
        if (inMemory != null) { f.setLastModified(System.currentTimeMillis()); return@withContext inMemory }
        if (!f.isFile) return@withContext null
        try {
            val obj = JSONObject(f.readText())
            val track = decode(k, artist, title, obj)
            synchronized(memory) { memory[k] = track }
            f.setLastModified(System.currentTimeMillis())
            track
        } catch (_: Exception) { f.delete(); null }
    }

    /**
     * The song's lyrics: from the cache, else from LRCLIB. Without a network yet (the car just started, the 4G not up) or
     * after a network error, tried again for this song (27/11: the first song after a start stayed without lyrics until
     * the next one): as soon as the network is there, then every [RETRY_MS], for [PATIENCE_MS] at most. Cancelled with
     * the caller's job when the song changes. Null: no lyrics found, or no network for that long.
     */
    suspend fun lookup(artist: String, title: String, durationMs: Long, force: Boolean = false): Track? = withContext(Dispatchers.IO) {
        if (!force) cached(artist, title)?.let { return@withContext it }
        val ctx = app ?: return@withContext null
        while (true) {
            when (val a = patiently(online = { networkAllowed(ctx) && online(ctx) }, wait = ::waitOrWifi) { lookupOnce(artist, title, durationMs) }) {
                is Attempt.Done -> return@withContext a.value
                // Still nothing for this song: tried again when a Wi-Fi connects or gets its internet back (Marc, 27/11)
                Attempt.Failed -> { Log.i(TAG, "lyrics: waiting for the Wi-Fi to come back"); awaitWifi() }
            }
        }
        @Suppress("UNREACHABLE_CODE") null
    }

    /** Bumped when a Wi-Fi connects or gets a validated internet: the waits below end early. */
    private val wifiGen = java.util.concurrent.atomic.AtomicInteger()

    private suspend fun waitOrWifi(ms: Long) {
        val g = wifiGen.get(); var left = ms
        while (left > 0 && wifiGen.get() == g) { val s = minOf(250L, left); kotlinx.coroutines.delay(s); left -= s }
    }

    private suspend fun awaitWifi() { val g = wifiGen.get(); while (wifiGen.get() == g) kotlinx.coroutines.delay(500) }

    private fun watchWifi(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            cm.registerNetworkCallback(android.net.NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), object : ConnectivityManager.NetworkCallback() {
                private val validated = HashSet<android.net.Network>()
                override fun onAvailable(network: android.net.Network) { wifiGen.incrementAndGet() }
                override fun onCapabilitiesChanged(network: android.net.Network, caps: NetworkCapabilities) {
                    val v = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    if (v && validated.add(network)) wifiGen.incrementAndGet() else if (!v) validated.remove(network)
                }
                override fun onLost(network: android.net.Network) { validated.remove(network) }
            })
        } catch (e: Exception) { Log.w(TAG, "no Wi-Fi callback: ${e.message}") }
    }

    /** One try: the track, nothing found (a definite answer), or a network failure to try again. */
    internal sealed class Attempt<out T> { data class Done<T>(val value: T?) : Attempt<T>(); object Failed : Attempt<Nothing>() }

    const val RETRY_MS = 10_000L
    const val PATIENCE_MS = 60_000L
    /** While waiting for the network: how often it is looked at. */
    const val ONLINE_POLL_MS = 1_000L

    /**
     * Runs [attempt] until it gives an answer: with no network it waits for one (looked at every [ONLINE_POLL_MS]), after a
     * failure it waits [RETRY_MS]; gives up after [PATIENCE_MS] of waiting ([Attempt.Failed]). Internal for the tests.
     */
    internal suspend fun <T> patiently(online: () -> Boolean, wait: suspend (Long) -> Unit, attempt: suspend () -> Attempt<T>): Attempt<T> {
        var waited = 0L
        var logged = false
        while (true) {
            if (online()) {
                when (val a = attempt()) {
                    is Attempt.Done -> { if (logged) Log.i(TAG, "lyrics found after ${waited / 1000} s of waiting"); return a }
                    Attempt.Failed -> if (waited + RETRY_MS > PATIENCE_MS) return Attempt.Failed else {
                        if (!logged) { logged = true; Log.i(TAG, "lyrics request failed: tried again every ${RETRY_MS / 1000} s") }
                        wait(RETRY_MS); waited += RETRY_MS
                    }
                }
            } else {
                if (waited + ONLINE_POLL_MS > PATIENCE_MS) { Log.i(TAG, "no network for lyrics for ${PATIENCE_MS / 1000} s: given up for now"); return Attempt.Failed }
                if (!logged) { logged = true; Log.i(TAG, "no network for lyrics yet: waiting for it") }
                wait(ONLINE_POLL_MS); waited += ONLINE_POLL_MS
            }
        }
    }

    private const val TAG = "Lyrics"

    /** The active network has internet (not yet validated is enough: the lyrics' request is the real test). */
    private fun online(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Exception) { false }

    private suspend fun lookupOnce(artist: String, title: String, durationMs: Long): Attempt<Track> {
        val k = key(artist, title)
        // Lyrics chosen in the picker for this song (26/11): those, by their id
        LyricsChoices.lyricsId(artist, title).takeIf { it > 0 }?.let { id ->
            try { JSONObject(request("$BASE/get/$id").body) } catch (_: Exception) { null }?.let { o ->
                val track = decode(k, artist, title, o)
                val (enabled, maxMb) = contextPrefs()
                if (enabled) { synchronized(memory) { memory.clear(); memory[k] = track }; persist(k, o, maxMb) }
                return Attempt.Done(track)
            }
        }
        val exact = linkedMapOf("artist_name" to artist, "track_name" to title)
        if (durationMs > 0) exact["duration"] = String.format(Locale.US, "%.2f", durationMs / 1000.0)
        var missing = false
        val found = try { request("$BASE/get?${query(exact)}")?.let { JSONObject(it.body) } } catch (e: HttpFailure) {
            // A 4xx other than 404: a definite refusal; 5xx: the server, tried again
            if (e.code != 404) return if (e.code >= 500) Attempt.Failed else Attempt.Done(null)
            missing = true
            null
        } catch (_: java.io.IOException) { return Attempt.Failed }
        catch (_: Exception) { return Attempt.Done(null) }
        if (found == null && !missing) return Attempt.Done(null)
        // Not found as given: searched, first with the whole artist, then with the main one only (26/11, #135:
        // CarPlay gives "A & B" or "A, B" and titles with "(feat. C)")
        // The exact answer without synced lyrics: a synced version of the same song searched for (#135)
        val better = found?.takeIf { !synced(it) && !it.optBoolean("instrumental", false) }?.let {
            searches(artist, title).firstNotNullOfOrNull { q ->
                try { best(JSONArray(request("$BASE/search?${query(mapOf("q" to q))}").body), artist, title, durationMs)?.takeIf(::synced) }
                catch (_: Exception) { null }
            }
        }
        val result = better ?: found ?: searches(artist, title).firstNotNullOfOrNull { q ->
            try {
                val arr = JSONArray(request("$BASE/search?${query(mapOf("q" to q))}").body)
                best(arr, artist, title, durationMs)
            } catch (_: Exception) { null }
        }
        if (result == null) return Attempt.Done(null)
        val track = decode(k, artist, title, result)
        val (enabled, maxMb) = contextPrefs()
        if (enabled) { synchronized(memory) { memory.clear(); memory[k] = track }; persist(k, result, maxMb) }
        return Attempt.Done(track)
    }

    /**
     * Every result LRCLIB has for the song (the picker, 26/11), best first as [best] ranks them, without repeats and
     * without those that have no lyrics; null when the search could not be made (no network, an error).
     */
    suspend fun search(artist: String, title: String, durationMs: Long): List<Found>? = withContext(Dispatchers.IO) {
        val ctx = app ?: return@withContext null
        if (!networkAllowed(ctx)) return@withContext null
        val all = LinkedHashMap<Long, JSONObject>()
        var answered = false
        for (q in searches(artist, title)) {
            try {
                val arr = JSONArray(request("$BASE/search?${query(mapOf("q" to q))}").body)
                answered = true
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { o -> all.getOrPut(o.optLong("id", -1L - all.size)) { o } }
            } catch (_: Exception) { }
        }
        if (!answered) return@withContext null
        ranked(all.values.toList(), artist, title, durationMs).map { o ->
            Found(o.optLong("id", 0), o.text("trackName"), o.text("artistName"), o.text("albumName"),
                (o.optDouble("duration", 0.0) * 1000).toLong(), o.text("syncedLyrics").isNotBlank(), o.optBoolean("instrumental", false), o)
        }
    }

    /** [found] chosen for the song in the picker: kept for it (its id, and in the cache) and returned as its lyrics. */
    suspend fun choose(artist: String, title: String, found: Found): Track = withContext(Dispatchers.IO) {
        val k = key(artist, title)
        val track = decode(k, artist, title, found.json)
        LyricsChoices.setLyrics(artist, title, found.id)
        val (enabled, maxMb) = contextPrefs()
        synchronized(memory) { memory.clear(); memory[k] = track }
        if (enabled) persist(k, found.json, maxMb)
        chosen.tryEmit(k)
        track
    }

    /** The key of a song whose lyrics were just chosen (the pill takes them at once). */
    val chosen = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)

    private fun contextPrefs(): Pair<Boolean, Int> {
        val ctx = app ?: return false to 20
        val p = LyricsCardPrefs(ctx)
        return p.cacheEnabled to p.cacheMaxMb
    }

    internal fun clean(s: String) = s
        // Guests in the title: "(feat. C)", "[with C]", " ft. C"
        .replace(Regex("(?i)\\s*[(\\[](feat\\.?|ft\\.?|featuring|with)\\s+[^)\\]]*[)\\]]"), " ")
        .replace(Regex("(?i)\\s+(feat\\.?|ft\\.|featuring)\\s+.+$"), " ")
        .replace(Regex("(?i)\\s*-\\s*(\\d{4}\\s+)?remaster(ed)?(\\s+\\d{4})?(\\s+version)?$"), " ")
        .replace(Regex("(?i)\\s*\\((official\\s+(music\\s+)?video|lyrics?|audio|visualizer|remastered[^)]*)\\)"), " ")
        .replace(Regex("(?i)\\s*\\[(official\\s+(music\\s+)?video|lyrics?|audio|remastered[^]]*)]"), " ")
        .replace(Regex("(?i)\\s*-\\s*(single|feat\\.?\\s+.+)$"), " ")
        .replace(Regex("\\s+"), " ").trim()

    /** The main artist of "A & B", "A, B", "A feat. B", "A x B". */
    internal fun mainArtist(s: String) =
        s.split(Regex("(?i)\\s*(,|&|;|/|\\s(feat\\.?|ft\\.|featuring|x|and|with)\\s)\\s*")).first().trim().ifEmpty { s.trim() }

    /** The searches tried in turn when the exact request found nothing, without repeats. */
    internal fun searches(artist: String, title: String): List<String> {
        val t = clean(title)
        return listOf(artist, mainArtist(artist)).map { a -> listOf(a.trim(), t).filter { it.isNotBlank() }.joinToString(" ") }
            .filter { it.isNotBlank() }.distinct()
    }

    private fun query(values: Map<String, String>) = values.entries.joinToString("&") {
        URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
    }

    private class HttpFailure(val code: Int) : Exception()
    /**
     * One request to LRCLIB (Marc, 27/11): over the Wi-Fi when one is connected, never the mobile network then (a failure
     * is tried again on the Wi-Fi when it reconnects or gets its internet back, see [lookup]); with no Wi-Fi, over the
     * mobile network. An HTTP answer (404…) is an answer, not a reason to try another network.
     */
    private fun request(address: String): HttpResponse = DataUsage.tag(DataUsage.Use.LYRICS) {
        val url = URL(address)
        val ctx = app
        val r = if (ctx == null) once(null, url) else
            Download.firstWorking(ctx, "lyrics", url.host, REQUEST_MS,
                lyricsPicks(Download.picks(ctx, LyricsCardPrefs(ctx).wifiOnly))) { net -> once(net, url) }
        if (r.code !in 200..299) throw HttpFailure(r.code)
        r
    }

    /** The Wi-Fi ones when a Wi-Fi is connected, all of them (the mobile network, Android's default) otherwise. */
    internal fun lyricsPicks(all: List<Download.Pick>) = all.filter { it.wifi }.ifEmpty { all }

    /** All the networks of one request: past it, the waits above take over. */
    private const val REQUEST_MS = 20_000L

    private fun once(net: android.net.Network?, url: URL): HttpResponse {
        val c = ((net?.openConnection(url) ?: url.openConnection()) as HttpURLConnection).apply {
            connectTimeout = 7000; readTimeout = 7000; requestMethod = "GET"
            setRequestProperty("User-Agent", "MG4Dash/1.7 Lyrics")
        }
        activeConnections += c
        try {
            val code = c.responseCode
            val payload = if (code in 200..299) c.inputStream.use { it.readBytes() } else ByteArray(0)
            return HttpResponse(code, String(payload, Charsets.UTF_8))
        } finally { activeConnections -= c; c.disconnect() }
    }

    /** A result's closeness score (title, artist, length) up to which it is the song itself. */
    private const val MATCH = 7

    /** [o] has synced lyrics. */
    private fun synced(o: JSONObject) = o.text("syncedLyrics").isNotBlank()

    internal fun best(arr: JSONArray, artist: String, title: String, duration: Long): JSONObject? =
        ranked((0 until arr.length()).mapNotNull { arr.optJSONObject(it) }, artist, title, duration).firstOrNull()

    /**
     * The results that have lyrics (or are instrumentals), best first: among those that match the song (title and artist
     * close, length within about 45 s), synced lyrics before plain text (#135, 27/11: "text only" was picked while synced
     * ones were there); then the closest.
     */
    internal fun ranked(list: List<JSONObject>, artist: String, title: String, duration: Long): List<JSONObject> {
        val a = mainArtist(artist).lowercase(Locale.ROOT).trim()
        val t = clean(title).lowercase(Locale.ROOT).trim()
        fun score(o: JSONObject): Int {
            val oa = mainArtist(o.text("artistName")).lowercase(Locale.ROOT)
            val ot = clean(o.text("trackName")).lowercase(Locale.ROOT)
            val dur = (o.optDouble("duration", 0.0) * 1000).toLong()
            return (if (ot == t) 0 else if (ot.contains(t) || t.contains(ot)) 2 else 8) +
                (if (oa == a) 0 else if (oa.contains(a) || a.contains(oa)) 2 else 8) +
                if (duration > 0 && dur > 0) (kotlin.math.abs(duration - dur) / 15000).toInt().coerceAtMost(8) else 0
        }
        return list
            .sortedWith(compareBy<JSONObject>({ o -> if (score(o) <= MATCH) (if (o.text("syncedLyrics").isNotBlank()) 0 else 1) else 2 }, { score(it) }))
            .filter { it.optBoolean("instrumental", false) || it.text("syncedLyrics").isNotBlank() || it.text("plainLyrics").isNotBlank() }
    }

    internal fun decode(key: String, artist: String, title: String, o: JSONObject): Track {
        val syncedText = o.text("syncedLyrics").takeIf { it.isNotBlank() }
        val synced = syncedText?.let { parse(it) }
        return Track(key, artist, title, synced?.takeIf { it.isNotEmpty() }, o.text("plainLyrics").takeIf { it.isNotBlank() }, o.optBoolean("instrumental", false),
            o.optLong("id", 0))
    }

    private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key, "").takeUnless { it == "null" }.orEmpty()

    internal fun parse(lrc: String): List<Pair<Long, String>> = lrc.lineSequence().flatMap { line ->
        val matches = Regex("\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?]").findAll(line).toList()
        val lyric = Regex("\\[\\d{1,2}:\\d{2}(?:\\.\\d{1,3})?]").replace(line, "").trim()
        matches.asSequence().mapNotNull { m ->
            val min = m.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val sec = m.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            val frac = m.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0
            (min * 60_000 + sec * 1000 + frac) to lyric
        }
    }.filter { it.second.isNotBlank() }.sortedBy { it.first }.toList()

    private suspend fun persist(key: String, json: JSONObject, maxMb: Int) {
        val ctx = app ?: return
        val dir = File(ctx.filesDir, "lyrics").apply { mkdirs() }
        val target = file(ctx, key)
        try {
            val temp = File(dir, target.name + ".tmp")
            temp.writeText(json.toString())
            if (temp.length() > maxMb * 1024L * 1024L) { temp.delete(); return }
            if (target.exists()) target.delete()
            temp.renameTo(target)
            trim(maxMb)
        } catch (_: Exception) { }
    }

    private fun file(ctx: Context, key: String): File {
        val name = java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(ctx.filesDir, "lyrics"), "$name.json")
    }

    suspend fun info(): CacheInfo = withContext(Dispatchers.IO) {
        val files = app?.let { File(it.filesDir, "lyrics").listFiles { f -> f.extension == "json" }?.toList() }.orEmpty()
        CacheInfo(files.sumOf { it.length() }, files.size)
    }

    suspend fun trim(maxMb: Int) = withContext(Dispatchers.IO) {
        val files = app?.let { File(it.filesDir, "lyrics").listFiles { f -> f.extension == "json" }?.toMutableList() }.orEmpty()
        var bytes = files.sumOf { it.length() }
        val max = maxMb.coerceIn(5, 100) * 1024L * 1024L
        files.sortedBy { it.lastModified() }.forEach { if (bytes > max) { bytes -= it.length(); it.delete() } }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        app?.let { File(it.filesDir, "lyrics").deleteRecursively() }
        synchronized(memory) { memory.clear() }
    }
}
