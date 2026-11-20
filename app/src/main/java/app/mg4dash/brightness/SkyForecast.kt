package app.mg4dash.brightness

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The sky forecast the light is estimated with (Open-Meteo, hourly: shortwave radiation in W/m², cloud cover in %),
 * kept on disk with the weather's four days, so that it still serves offline and after a restart.
 */
object SkyForecast {
    data class Hour(val at: Long, val radiation: Double?, val cloud: Double?)

    @Volatile private var hours: List<Hour> = emptyList()
    private var file: File? = null

    /** Read back from disk at the start. */
    fun load(dir: File) {
        file = File(dir, "sky.json")
        hours = try { parseStored(file!!.readText()) } catch (_: Exception) { emptyList() }
    }

    /** Hours of Open-Meteo's answer [json] (its "hourly" block, local times with "utc_offset_seconds"); kept and saved. */
    fun update(json: JSONObject) {
        val h = json.optJSONObject("hourly") ?: return
        val times = h.optJSONArray("time") ?: return
        val rad = h.optJSONArray("shortwave_radiation"); val cloud = h.optJSONArray("cloud_cover")
        if (rad == null && cloud == null) return
        val offset = json.optLong("utc_offset_seconds", 0L) * 1000L
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val out = (0 until times.length()).mapNotNull { i ->
            val at = try { fmt.parse(times.getString(i))!!.time - offset } catch (_: Exception) { return@mapNotNull null }
            Hour(at, rad?.takeIf { !it.isNull(i) }?.optDouble(i), cloud?.takeIf { !it.isNull(i) }?.optDouble(i))
        }
        if (out.isEmpty()) return
        hours = out
        try { file?.writeText(JSONArray().apply { out.forEach { put(JSONObject().put("t", it.at).put("r", it.radiation ?: JSONObject.NULL).put("c", it.cloud ?: JSONObject.NULL)) } }.toString()) } catch (_: Exception) {}
    }

    internal fun parseStored(s: String): List<Hour> {
        val a = JSONArray(s)
        return (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            Hour(o.getLong("t"), if (o.isNull("r")) null else o.getDouble("r"), if (o.isNull("c")) null else o.getDouble("c"))
        }
    }

    /** For the tests. */
    internal fun set(h: List<Hour>) { hours = h }

    /**
     * The sky at [now]: radiation and cloud cover between the two hours around it (Open-Meteo's radiation is the mean
     * of the hour before), null beyond the forecast (offline for more than its days: the sun's height alone).
     */
    fun at(now: Long): Pair<Double?, Double?>? {
        val h = hours
        if (h.isEmpty() || now < h.first().at - 3_600_000L || now > h.last().at + 3_600_000L) return null
        val i = h.indexOfLast { it.at <= now }
        if (i < 0) return h.first().radiation to h.first().cloud
        if (i == h.size - 1) return h[i].radiation to h[i].cloud
        val a = h[i]; val b = h[i + 1]
        val f = (now - a.at).toDouble() / (b.at - a.at).coerceAtLeast(1)
        fun mix(x: Double?, y: Double?) = if (x == null) y else if (y == null) x else x + (y - x) * f
        return mix(a.radiation, b.radiation) to mix(a.cloud, b.cloud)
    }
}
