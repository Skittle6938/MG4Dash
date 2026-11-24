package app.mg4dash.weather

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * A city found by its name (24/11: the weather's fallback city, searched instead of picked in a fixed list), from
 * Open-Meteo's geocoding, the same service as the weather (no key).
 */
object Geocoding {
    data class Place(val name: String, val region: String?, val country: String?, val lat: Double, val lon: Double) {
        /** "Lyon — Auvergne-Rhône-Alpes, France". */
        val label get() = name + listOfNotNull(region, country).filter { it.isNotBlank() }.joinToString(", ").let { if (it.isEmpty()) "" else " — $it" }
    }

    /** The places of Open-Meteo's answer, in its order (the most likely first). */
    fun parse(json: String): List<Place> {
        val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val r = results.optJSONObject(i) ?: return@mapNotNull null
            val name = r.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (!r.has("latitude") || !r.has("longitude")) return@mapNotNull null
            Place(name, r.optString("admin1").takeIf { it.isNotBlank() }, r.optString("country").takeIf { it.isNotBlank() },
                r.getDouble("latitude"), r.getDouble("longitude"))
        }
    }

    /** Up to ten places named like [query]; null without network (or an error), empty when none is found. */
    fun search(context: Context, query: String): List<Place>? = app.mg4dash.system.DataUsage.tag(app.mg4dash.system.DataUsage.Use.WEATHER) {
        try {
            val url = "https://geocoding-api.open-meteo.com/v1/search?count=10&format=json&language=" + Locale.getDefault().language +
                "&name=" + URLEncoder.encode(query.trim(), "UTF-8")
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            try { if (c.responseCode != 200) null else parse(c.inputStream.bufferedReader().use { it.readText() }) } finally { c.disconnect() }
        } catch (e: Exception) {
            app.mg4dash.system.Log.w("Weather", "city search: ${e.message}"); null
        }
    }
}
