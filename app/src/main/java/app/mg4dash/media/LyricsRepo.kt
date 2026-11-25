package app.mg4dash.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.mg4dash.cards.LyricsCardPrefs
import app.mg4dash.system.DataUsage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** LRCLIB lookup and size bounded LRU cache. All public disk/network operations run on IO. */
object LyricsRepo {
    data class Track(val key: String, val artist: String, val title: String, val synced: List<Pair<Long, String>>?, val plain: String?, val instrumental: Boolean = false)
    data class CacheInfo(val bytes: Long, val tracks: Int)
    private data class HttpResponse(val body: String)

    private const val BASE = "https://lrclib.net/api"
    @Volatile private var app: Context? = null
    private val memory = LinkedHashMap<String, Track>()
    private val activeConnections = java.util.concurrent.ConcurrentHashMap.newKeySet<HttpURLConnection>()
    fun start(context: Context) { app = context.applicationContext; LyricsCardPrefs(context) }

    fun cancelActiveRequest() {
        activeConnections.toList().forEach { connection -> try { connection.disconnect() } catch (_: Exception) { } }
    }

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

    suspend fun lookup(artist: String, title: String, durationMs: Long, force: Boolean = false): Track? = withContext(Dispatchers.IO) {
        val ctx = app ?: return@withContext null
        val k = key(artist, title)
        if (!force) cached(artist, title)?.let { return@withContext it }
        if (!networkAllowed(ctx)) return@withContext null
        val exact = linkedMapOf("artist_name" to artist, "track_name" to title)
        if (durationMs > 0) exact["duration"] = String.format(Locale.US, "%.2f", durationMs / 1000.0)
        var missing = false
        val found = try { request("$BASE/get?${query(exact)}")?.let { JSONObject(it.body) } } catch (e: HttpFailure) {
            if (e.code != 404) return@withContext null
            missing = true
            null
        } catch (_: Exception) { return@withContext null }
        val result = found ?: try {
            if (!missing) return@withContext null
            val q = listOf(artist, clean(title)).filter { it.isNotBlank() }.joinToString(" ")
            val arr = JSONArray(request("$BASE/search?${query(mapOf("q" to q))}")?.body ?: return@withContext null)
            best(arr, artist, title, durationMs)
        } catch (_: Exception) { null }
        if (result == null) return@withContext null
        val track = decode(k, artist, title, result)
        val (enabled, maxMb) = contextPrefs()
        if (enabled) { synchronized(memory) { memory.clear(); memory[k] = track }; persist(k, result, maxMb) }
        track
    }

    private fun contextPrefs(): Pair<Boolean, Int> {
        val ctx = app ?: return false to 20
        val p = LyricsCardPrefs(ctx)
        return p.cacheEnabled to p.cacheMaxMb
    }

    private fun clean(s: String) = s
        .replace(Regex("(?i)\\s*\\((official\\s+(music\\s+)?video|lyrics?|audio|visualizer|remastered[^)]*)\\)"), " ")
        .replace(Regex("(?i)\\s*\\[(official\\s+(music\\s+)?video|lyrics?|audio|remastered[^]]*)]"), " ")
        .replace(Regex("(?i)\\s*-\\s*(single|feat\\.?\\s+.+)$"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun query(values: Map<String, String>) = values.entries.joinToString("&") {
        URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
    }

    private class HttpFailure(val code: Int) : Exception()
    private fun request(address: String): HttpResponse = DataUsage.tag(DataUsage.Use.LYRICS) {
        val c = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000; readTimeout = 7000; requestMethod = "GET"
            setRequestProperty("User-Agent", "MG4Dash/1.7 Lyrics")
        }
        activeConnections += c
        try {
            val code = c.responseCode
            if (code !in 200..299) throw HttpFailure(code)
            val payload = c.inputStream.use { it.readBytes() }
            HttpResponse(String(payload, Charsets.UTF_8))
        } finally { activeConnections -= c; c.disconnect() }
    }

    internal fun best(arr: JSONArray, artist: String, title: String, duration: Long): JSONObject? {
        val a = artist.lowercase(Locale.ROOT).trim()
        val t = clean(title).lowercase(Locale.ROOT).trim()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .sortedBy { o ->
                val oa = o.text("artistName").lowercase(Locale.ROOT)
                val ot = clean(o.text("trackName")).lowercase(Locale.ROOT)
                val dur = (o.optDouble("duration", 0.0) * 1000).toLong()
                (if (ot == t) 0 else if (ot.contains(t) || t.contains(ot)) 2 else 8) +
                    (if (oa == a) 0 else if (oa.contains(a) || a.contains(oa)) 2 else 8) +
                    if (duration > 0 && dur > 0) (kotlin.math.abs(duration - dur) / 15000).toInt().coerceAtMost(8) else 0
            }.firstOrNull { it.optBoolean("instrumental", false) || it.text("syncedLyrics").isNotBlank() || it.text("plainLyrics").isNotBlank() }
    }

    internal fun decode(key: String, artist: String, title: String, o: JSONObject): Track {
        val syncedText = o.text("syncedLyrics").takeIf { it.isNotBlank() }
        val synced = syncedText?.let { parse(it) }
        return Track(key, artist, title, synced?.takeIf { it.isNotEmpty() }, o.text("plainLyrics").takeIf { it.isNotBlank() }, o.optBoolean("instrumental", false))
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
