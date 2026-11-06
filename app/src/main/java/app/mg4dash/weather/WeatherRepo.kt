package app.mg4dash.weather

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import app.mg4dash.BuildConfig
import app.mg4dash.R
import app.mg4dash.Tr
import app.mg4dash.system.Log
import app.mg4dash.system.Prefs
import app.mg4dash.system.StatusMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Open-Meteo weather (choice 4A): free, no key, needs internet in the car.
 * Position: the system's last known location, otherwise the city set in the settings.
 */
object WeatherRepo {
    private const val TAG = "Weather"
    private const val REFRESH_MS = 30 * 60 * 1000L
    private const val RETRY_MS = 2 * 60 * 1000L
    /** Distance driven since the last weather that triggers a new request (issue #18). */
    private const val MOVE_M = 5000f
    /** Distance that triggers an update of the city name alone, more frequent than the weather. */
    private const val CITY_MOVE_M = 2000f
    /** Minimum delay between two city name attempts outside a weather request (offline failures). */
    private const val CITY_RETRY_MS = 60_000L
    /**
     * Successive failures: quick new attempt, capped at 30 s. The map waits for internet to return
     * to reappear, and on the car the T-box network stays "available" during a 4G
     * outage: no network callback then wakes the loop (found on 2026-10-18).
     */
    private val FAIL_DELAYS = longArrayOf(15_000L, 30_000L)
    /** Outage shorter than this: the map stays shown (cached tiles), no switch. */
    private const val OFFLINE_GRACE_MS = 60_000L

    data class Hour(val label: String, val code: Int, val tempC: Int)
    data class Day(val label: String, val code: Int, val minC: Int, val maxC: Int)
    data class Weather(
        val place: String,
        val tempC: Int,
        val feelsC: Int,
        val code: Int,
        val windKmh: Int,
        val humidity: Int,
        val hours: List<Hour>,
        val days: List<Day>,
        val updatedAt: Long,
        val todayMin: Int? = null,
        val todayMax: Int? = null,
        /** Today's chance of rain, in % (#34). */
        val rainPct: Int? = null,
    )

    private val _weather = MutableStateFlow<Weather?>(null)
    val weather: StateFlow<Weather?> = _weather
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Last request failed (touch, 5 km driven or 30 min refresh). */
    private val _failed = MutableStateFlow(false)
    /**
     * Weather to show. A failed request keeps the last forecast as long as it is less than
     * [STALE_MS] old; beyond that (or with no forecast), offline display: time and outside
     * temperature (issue #18). Re-evaluated every 5 min, even with no new request.
     */
    val shown: StateFlow<Weather?> = kotlinx.coroutines.flow.combine(_weather, _failed,
        kotlinx.coroutines.flow.flow { while (true) { emit(Unit); kotlinx.coroutines.delay(300_000) } }) { w, f, _ ->
        if (w != null && (!f || System.currentTimeMillis() - w.updatedAt < STALE_MS)) w else null
    }.stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)
    private const val STALE_MS = 30 * 60_000L   // slightly old weather is better than nothing (owner's choice, #18)

    fun start(context: Context) {
        val app = context.applicationContext
        if (BuildConfig.MOCK) {
            if (Prefs(app).raw.getBoolean("mock_offline", false)) return
            _weather.value = Weather(
                "Paris", 14, 12, 2, 18, 72,
                listOf(Hour("14h", 2, 14), Hour("15h", 3, 15), Hour("16h", 61, 13), Hour("17h", 61, 12), Hour("18h", 3, 11)),
                listOf(Day("Mer", 3, 8, 15), Day("Jeu", 0, 7, 17), Day("Ven", 61, 9, 13)),
                System.currentTimeMillis(), todayMin = 9, todayMax = 17, rainPct = 20,
            )
            return
        }
        watchNetwork(app)
        // Between two weather updates (30 min), a small request every 2 min sees the loss of connection: the map
        // then goes offline quickly (#18). Successful weather stays shown (up to STALE_MS).
        scope.launch {
            delay(60_000)
            while (true) {
                if (failingSince == 0L) {
                    val ok = probe()
                    probeFailingSince = if (ok) 0L else probeFailingSince.takeIf { it != 0L } ?: System.currentTimeMillis()
                    if (!ok) Log.i(TAG, "connection check failed")
                }
                delay(if (probeFailingSince != 0L) 30_000L else PROBE_MS)
            }
        }
        scope.launch {
            delay(5_000)   // let the T-box network come up after startup
            var fails = 0
            while (true) {
                val ok = try { refresh(app) } catch (e: Exception) {
                    Log.w(TAG, "failed: ${e.javaClass.simpleName} ${e.message}"); false
                }
                lastFailed = !ok
                _failed.value = !ok
                failingSince = if (ok) 0L else failingSince.takeIf { it != 0L } ?: System.currentTimeMillis()
                // Failure (no network): retry quickly. Successful but with no position or city
                // name: also retry, more calmly, instead of waiting 30 min.
                val wait = when {
                    !ok -> FAIL_DELAYS[minOf(fails++, FAIL_DELAYS.size - 1)]
                    usedFallback || missingCity -> { fails = 0; RETRY_MS }
                    else -> { fails = 0; REFRESH_MS }
                }
                if (!ok || usedFallback || missingCity) Log.i(TAG, "retry in ${wait / 1000} s (network ${if (ok) "ok" else "missing"}, location ${if (usedFallback) "missing" else "ok"}, city ${if (missingCity) "missing" else "ok"})")
                // The network's return (or a position) wakes the loop without waiting for the delay to end
                kotlinx.coroutines.withTimeoutOrNull(wait) { wake.receive() }
            }
        }
    }

    @Volatile private var lastFailed = false
    /** Start of the current series of failures (0: last request succeeded). */
    @Volatile private var failingSince = 0L
    /**
     * Weather requests failing for more than a minute (no real connection), even if Android
     * sees a network. A brief outage (tunnel, dead zone) no longer makes the map disappear.
     */
    val offline: Boolean get() = listOf(failingSince, probeFailingSince).any { it != 0L && System.currentTimeMillis() - it > OFFLINE_GRACE_MS }
    @Volatile private var probeFailingSince = 0L
    private const val PROBE_MS = 2 * 60 * 1000L

    /** Real internet access: HEAD request with no content to the weather service (≈ 1 KB). */
    private fun probe(): Boolean = app.mg4dash.system.DataUsage.tag(app.mg4dash.system.DataUsage.Use.WEATHER) { probeUntagged() }
    private fun probeUntagged(): Boolean = try {
        val c = URL("https://api.open-meteo.com/").openConnection() as HttpURLConnection
        c.requestMethod = "HEAD"; c.connectTimeout = 8000; c.readTimeout = 8000; c.instanceFollowRedirects = false
        try { c.responseCode > 0 } finally { c.disconnect() }
    } catch (_: Exception) { false }
    @Volatile private var missingCity = false
    private val wake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    /** As soon as the car gets internet back, missing or incomplete weather is requested again. */
    private fun watchNetwork(context: Context) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    if (lastFailed || usedFallback || missingCity || _weather.value == null) {
                        Log.i(TAG, "network available: retrying")
                        wake.trySend(Unit)
                    }
                }
            })
        } catch (e: Exception) { Log.w(TAG, "network tracking failed: ${e.message}") }
    }

    @Volatile private var usedFallback = false

    /**
     * At startup, the weather is requested before the first GPS fix: it then used the
     * fallback city until the next refresh. As soon as a position arrives, it is requested again.
     */
    fun onLocation(context: Context) {
        if (usedFallback) { usedFallback = false; wake.trySend(Unit); return }
        // While driving, the weather followed the car up to 30 min late (issue #18): after
        // MOVE_M driven since the last successful weather, request it again. Nothing if a request is
        // already running or if the last one failed (the loop then handles its own retries).
        val from = fetchedAt ?: return
        val cur = app.mg4dash.map.LocationRepo.location.value ?: return
        if (busy || lastFailed) return
        updateCity(context, cur.latitude, cur.longitude)
        val dist = FloatArray(1)
        android.location.Location.distanceBetween(from.first, from.second, cur.latitude, cur.longitude, dist)
        if (dist[0] >= MOVE_M) {
            busy = true
            Log.i(TAG, "${Math.round(dist[0] / 1000)} km driven: new weather")
            wake.trySend(Unit)
        }
    }

    /**
     * City name alone, with no new weather: requested again after CITY_MOVE_M since the last name
     * obtained, one request at a time and at most one per CITY_RETRY_MS.
     */
    private fun updateCity(context: Context, lat: Double, lon: Double) {
        val at = cityAt ?: return
        if (cityBusy || System.currentTimeMillis() - cityTry < CITY_RETRY_MS) return
        val dist = FloatArray(1)
        android.location.Location.distanceBetween(at.first, at.second, lat, lon, dist)
        if (dist[0] < CITY_MOVE_M) return
        cityBusy = true; cityTry = System.currentTimeMillis()
        scope.launch {
            try {
                val name = cityName(context.applicationContext, lat, lon) ?: return@launch
                _weather.value?.let { if (it.place != name) { _weather.value = it.copy(place = name); Log.i(TAG, "city: $name") } }
            } finally { cityBusy = false }
        }
    }

    /** Position of the last city name obtained (null while none). */
    @Volatile private var cityAt: Pair<Double, Double>? = null
    @Volatile private var cityBusy = false
    @Volatile private var cityTry = 0L

    /** Position of the last successful weather (null: fallback city or no weather yet). */
    @Volatile private var fetchedAt: Pair<Double, Double>? = null
    /** Request running or already asked: avoids waking the loop at each GPS fix. */
    @Volatile private var busy = false

    fun refreshNow(context: Context) {
        scope.launch {
            val ok = try { refresh(context.applicationContext) } catch (e: Exception) {
                Log.w(TAG, "failed: ${e.javaClass.simpleName} ${e.message}"); false
            }
            _failed.value = !ok
            if (!ok) { lastFailed = true; failingSince = failingSince.takeIf { it != 0L } ?: System.currentTimeMillis(); wake.trySend(Unit) }
        }
    }

    // No prior connectivity test: on the car, internet goes through the T-box's Ethernet
    // interface, and the request itself is the only reliable test.
    private fun refresh(context: Context): Boolean {
        busy = true
        try { return app.mg4dash.system.DataUsage.tag(app.mg4dash.system.DataUsage.Use.WEATHER) { doRefresh(context) } } finally { busy = false }
    }

    private fun doRefresh(context: Context): Boolean {
        Log.i(TAG, "Open-Meteo request (internet: ${StatusMonitor.hasInternet(context)})")
        val prefs = Prefs(context)
        val loc = app.mg4dash.map.LocationRepo.location.value?.let { it.latitude to it.longitude } ?: lastLocation(context)
        usedFallback = loc == null
        val lat = loc?.first ?: prefs.weatherLat.toDouble()
        val lon = loc?.second ?: prefs.weatherLon.toDouble()
        val name = loc?.let { cityName(context, it.first, it.second) }
        missingCity = loc != null && name == null
        val place = if (loc != null) (name ?: Tr.s(R.string.weather_here)) else prefs.weatherCity
        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,is_day" +
                "&hourly=temperature_2m,weather_code,is_day&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&forecast_days=4&timezone=auto",
            lat, lon,
        ) + prefs.weatherModel.takeIf { it.isNotBlank() }?.let { "&models=$it" }.orEmpty()   // chosen model (#67)
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        val body = try {
            if (conn.responseCode != 200) { Log.w(TAG, "HTTP ${conn.responseCode}"); return false }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
        _weather.value = parse(JSONObject(body), place)
        Log.i(TAG, "updated: ${_weather.value?.tempC}° in $place")
        fetchedAt = loc
        return true
    }

    /**
     * Approximate city name (OpenStreetMap Nominatim, reverse geocoding), cached:
     * new request only after 5 km or on language change (issue #6: show the city
     * rather than "Current position"). Null offline or on failure.
     */
    private fun cityName(context: Context, lat: Double, lon: Double): String? = app.mg4dash.system.DataUsage.tag(app.mg4dash.system.DataUsage.Use.WEATHER) { cityNameUntagged(context, lat, lon) }
    private fun cityNameUntagged(context: Context, lat: Double, lon: Double): String? {
        val sp = Prefs(context).raw
        val lang = Locale.getDefault().language
        val cLat = sp.getFloat("city_lat", 0f).toDouble(); val cLon = sp.getFloat("city_lon", 0f).toDouble()
        val cached = sp.getString("city_name", null)
        val dist = FloatArray(1)
        android.location.Location.distanceBetween(lat, lon, cLat, cLon, dist)
        if (cityAt == null && cached != null) cityAt = cLat to cLon
        if (cached != null && dist[0] < CITY_MOVE_M && sp.getString("city_lang", "") == lang) return cached
        return try {
            val url = String.format(Locale.US, "https://nominatim.openstreetmap.org/reverse?format=json&lat=%.4f&lon=%.4f&zoom=10&accept-language=%s", lat, lon, lang)
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "MG4Dash (github.com/Skittle6938/MG4Dash)")
            val body = try { if (c.responseCode != 200) return cached; c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
            val a = JSONObject(body).optJSONObject("address") ?: return cached
            val name = listOf("city", "town", "village", "municipality", "county").map { a.optString(it) }.firstOrNull { it.isNotBlank() } ?: return cached
            sp.edit().putString("city_name", name).putFloat("city_lat", lat.toFloat()).putFloat("city_lon", lon.toFloat()).putString("city_lang", lang).apply()
            cityAt = lat to lon
            name
        } catch (e: Exception) {
            Log.w(TAG, "city: ${e.message}"); cached
        }
    }

    private fun parse(json: JSONObject, place: String): Weather {
        val cur = json.getJSONObject("current")
        val curTime = cur.getString("time")             // "2026-10-13T15:00"
        val hourly = json.getJSONObject("hourly")
        val hTimes = hourly.getJSONArray("time")
        val hTemps = hourly.getJSONArray("temperature_2m")
        val hCodes = hourly.getJSONArray("weather_code")
        val hDay = hourly.optJSONArray("is_day")
        // Clear sky at night: moon and "Clear" instead of the sun (issue #7)
        fun night(code: Int, isDay: Int) = if (isDay == 0 && code <= 1) NIGHT_CLEAR else code
        var start = 0
        for (i in 0 until hTimes.length()) if (hTimes.getString(i) <= curTime) start = i
        val hours = (1..5).mapNotNull { k ->
            val i = start + k
            // Missing value (chosen model outside its area or duration, #67): hour skipped
            if (i >= hTimes.length() || hCodes.isNull(i) || hTemps.isNull(i)) null
            else Hour(hourLabel(hTimes.getString(i)), night(hCodes.getInt(i), hDay?.optInt(i, 1) ?: 1), Math.round(hTemps.getDouble(i)).toInt())
        }
        val daily = json.getJSONObject("daily")
        val dTimes = daily.getJSONArray("time")
        val days = (1 until minOf(4, dTimes.length())).filter { i ->
            listOf("weather_code", "temperature_2m_min", "temperature_2m_max").none { daily.getJSONArray(it).isNull(i) }
        }.map { i ->
            Day(
                dayLabel(dTimes.getString(i)),
                daily.getJSONArray("weather_code").getInt(i),
                Math.round(daily.getJSONArray("temperature_2m_min").getDouble(i)).toInt(),
                Math.round(daily.getJSONArray("temperature_2m_max").getDouble(i)).toInt(),
            )
        }
        return Weather(
            place = place,
            tempC = Math.round(cur.getDouble("temperature_2m")).toInt(),
            feelsC = Math.round(cur.getDouble("apparent_temperature")).toInt(),
            code = night(cur.getInt("weather_code"), cur.optInt("is_day", 1)),
            windKmh = Math.round(cur.getDouble("wind_speed_10m")).toInt(),
            rainPct = daily.optJSONArray("precipitation_probability_max")?.takeIf { !it.isNull(0) }?.optInt(0),
            humidity = cur.getInt("relative_humidity_2m"),
            hours = hours,
            days = days,
            updatedAt = System.currentTimeMillis(),
            todayMin = runCatching { Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)).toInt() }.getOrNull(),
            todayMax = runCatching { Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)).toInt() }.getOrNull(),
        )
    }

    private fun dayLabel(iso: String): String = try {
        val d = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso)
        java.text.SimpleDateFormat("EEE", Locale.getDefault()).format(d!!).trimEnd('.').replaceFirstChar { it.uppercase() }
    } catch (_: Exception) { iso.takeLast(5) }

    /** "14h" in French, "14:00" elsewhere. */
    private fun hourLabel(iso: String): String =
        if (Locale.getDefault().language == "fr") iso.substring(11, 13).trimStart('0').ifEmpty { "0" } + "h"
        else iso.substring(11, 16)

    @SuppressLint("MissingPermission")
    private fun lastLocation(context: Context): Pair<Double, Double>? = try {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm.getProviders(true)
            .mapNotNull { lm.getLastKnownLocation(it) }
            .maxByOrNull { it.time }
            ?.takeIf { System.currentTimeMillis() - it.time < 6 * 3600_000L }
            ?.let { it.latitude to it.longitude }
    } catch (_: Exception) { null }

    /** Internal code: clear sky at night (WMO codes 0 and 1 when it is night). */
    const val NIGHT_CLEAR = 100

    /** WMO codes → French label. */
    fun label(code: Int): String = when (code) {
        NIGHT_CLEAR -> Tr.s(R.string.wx_clear)
        0 -> Tr.s(R.string.wx_sunny)
        1 -> Tr.s(R.string.wx_mostly_clear)
        2 -> Tr.s(R.string.wx_partly)
        3 -> Tr.s(R.string.wx_overcast)
        45, 48 -> Tr.s(R.string.wx_fog)
        51, 53, 55, 56, 57 -> Tr.s(R.string.wx_drizzle)
        61, 63, 65, 66, 67, 80, 81, 82 -> Tr.s(R.string.wx_rain)
        71, 73, 75, 77, 85, 86 -> Tr.s(R.string.wx_snow)
        95, 96, 99 -> Tr.s(R.string.wx_storm)
        else -> "—"
    }

    /** Icon colour: orange sun, cyan rain, orange storm, light snow and clouds. */
    fun iconColor(code: Int): Int = when (code) {
        NIGHT_CLEAR -> R.color.text_value
        0, 1 -> R.color.accent_heat
        2, 3, 45, 48 -> R.color.text_value
        71, 73, 75, 77, 85, 86 -> R.color.text_value
        95, 96, 99 -> R.color.accent_heat
        else -> if (code >= 51) R.color.accent_cold else R.color.text_value
    }

    fun icon(code: Int): Int = when (code) {
        NIGHT_CLEAR -> R.drawable.ic_moon
        0, 1 -> R.drawable.ic_sun
        2, 3, 45, 48 -> R.drawable.ic_cloud
        71, 73, 75, 77, 85, 86 -> R.drawable.ic_snow
        95, 96, 99 -> R.drawable.ic_bolt
        else -> if (code >= 51) R.drawable.ic_rain else R.drawable.ic_cloud
    }
}
