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
 * Météo Open-Meteo (choix 4A) : gratuit, sans clé, nécessite internet dans la voiture.
 * Position : dernière localisation connue du système, sinon la ville réglée dans les paramètres.
 */
object WeatherRepo {
    private const val TAG = "Météo"
    private const val REFRESH_MS = 30 * 60 * 1000L
    private const val RETRY_MS = 2 * 60 * 1000L
    /** Distance parcourue depuis la dernière météo qui déclenche une nouvelle requête (issue #18). */
    private const val MOVE_M = 5000f
    /** Distance qui déclenche la mise à jour du seul nom de ville, plus fréquente que la météo. */
    private const val CITY_MOVE_M = 2000f
    /** Délai minimal entre deux essais de nom de ville hors requête météo (échecs hors ligne). */
    private const val CITY_RETRY_MS = 60_000L
    /**
     * Échecs successifs : nouvel essai rapide, plafonné à 30 s. La carte attend le retour d'internet
     * pour réapparaître, et sur la voiture le réseau T-box reste « disponible » pendant une coupure
     * 4G : aucun rappel réseau ne réveille alors la boucle (constaté le 2026-10-18).
     */
    private val FAIL_DELAYS = longArrayOf(15_000L, 30_000L)
    /** Coupure plus courte que ça : la carte reste affichée (tuiles en cache), pas de bascule. */
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
        /** Risque de pluie du jour, en % (#34). */
        val rainPct: Int? = null,
    )

    private val _weather = MutableStateFlow<Weather?>(null)
    val weather: StateFlow<Weather?> = _weather
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Dernière requête en échec (toucher, 5 km parcourus ou rafraîchissement des 30 min). */
    private val _failed = MutableStateFlow(false)
    /**
     * Météo à afficher. Une requête en échec garde les dernières prévisions tant qu'elles ont moins
     * de [STALE_MS] ; au-delà (ou sans prévisions), affichage hors ligne : heure et température
     * extérieure (issue #18). Réévalué toutes les 5 min, même sans nouvelle requête.
     */
    val shown: StateFlow<Weather?> = kotlinx.coroutines.flow.combine(_weather, _failed,
        kotlinx.coroutines.flow.flow { while (true) { emit(Unit); kotlinx.coroutines.delay(300_000) } }) { w, f, _ ->
        if (w != null && (!f || System.currentTimeMillis() - w.updatedAt < STALE_MS)) w else null
    }.stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)
    private const val STALE_MS = 3 * 3_600_000L

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
        scope.launch {
            delay(5_000)   // laisser le réseau T-box monter après le démarrage
            var fails = 0
            while (true) {
                val ok = try { refresh(app) } catch (e: Exception) {
                    Log.w(TAG, "échec : ${e.javaClass.simpleName} ${e.message}"); false
                }
                lastFailed = !ok
                _failed.value = !ok
                failingSince = if (ok) 0L else failingSince.takeIf { it != 0L } ?: System.currentTimeMillis()
                // Échec (pas de réseau) : on réessaie vite. Réussi mais sans position ou sans nom de
                // ville : on réessaie aussi, plus calmement, au lieu d'attendre 30 min.
                val wait = when {
                    !ok -> FAIL_DELAYS[minOf(fails++, FAIL_DELAYS.size - 1)]
                    usedFallback || missingCity -> { fails = 0; RETRY_MS }
                    else -> { fails = 0; REFRESH_MS }
                }
                if (!ok || usedFallback || missingCity) Log.i(TAG, "nouvel essai dans ${wait / 1000} s (réseau ${if (ok) "ok" else "absent"}, position ${if (usedFallback) "absente" else "ok"}, ville ${if (missingCity) "absente" else "ok"})")
                // Le retour du réseau (ou une position) réveille la boucle sans attendre la fin du délai
                kotlinx.coroutines.withTimeoutOrNull(wait) { wake.receive() }
            }
        }
    }

    @Volatile private var lastFailed = false
    /** Début de la série d'échecs en cours (0 : dernière requête réussie). */
    @Volatile private var failingSince = 0L
    /**
     * Requêtes météo en échec depuis plus d'une minute (pas de connexion réelle), même si Android
     * voit un réseau. Une coupure brève (tunnel, zone blanche) ne fait plus disparaître la carte.
     */
    val offline: Boolean get() = failingSince != 0L && System.currentTimeMillis() - failingSince > OFFLINE_GRACE_MS
    @Volatile private var missingCity = false
    private val wake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    /** Dès que la voiture retrouve internet, une météo manquante ou incomplète est redemandée. */
    private fun watchNetwork(context: Context) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    if (lastFailed || usedFallback || missingCity || _weather.value == null) {
                        Log.i(TAG, "réseau disponible : nouvel essai")
                        wake.trySend(Unit)
                    }
                }
            })
        } catch (e: Exception) { Log.w(TAG, "suivi du réseau impossible : ${e.message}") }
    }

    @Volatile private var usedFallback = false

    /**
     * Au démarrage, la météo est demandée avant le premier relevé GPS : elle partait alors sur la
     * ville de repli jusqu'au rafraîchissement suivant. Dès qu'une position arrive, on la redemande.
     */
    fun onLocation(context: Context) {
        if (usedFallback) { usedFallback = false; wake.trySend(Unit); return }
        // En roulant, la météo suivait la voiture avec jusqu'à 30 min de retard (issue #18) : après
        // MOVE_M parcourus depuis la dernière météo réussie, on la redemande. Rien si une requête est
        // déjà en cours ou si la dernière a échoué (la boucle gère alors ses propres nouveaux essais).
        val from = fetchedAt ?: return
        val cur = app.mg4dash.map.LocationRepo.location.value ?: return
        if (busy || lastFailed) return
        updateCity(context, cur.latitude, cur.longitude)
        val dist = FloatArray(1)
        android.location.Location.distanceBetween(from.first, from.second, cur.latitude, cur.longitude, dist)
        if (dist[0] >= MOVE_M) {
            busy = true
            Log.i(TAG, "${Math.round(dist[0] / 1000)} km parcourus : nouvelle météo")
            wake.trySend(Unit)
        }
    }

    /**
     * Nom de ville seul, sans nouvelle météo : redemandé après CITY_MOVE_M depuis le dernier nom
     * obtenu, une requête à la fois et au plus une par CITY_RETRY_MS.
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
                _weather.value?.let { if (it.place != name) { _weather.value = it.copy(place = name); Log.i(TAG, "ville : $name") } }
            } finally { cityBusy = false }
        }
    }

    /** Position du dernier nom de ville obtenu (null tant qu'aucun). */
    @Volatile private var cityAt: Pair<Double, Double>? = null
    @Volatile private var cityBusy = false
    @Volatile private var cityTry = 0L

    /** Position de la dernière météo réussie (null : ville de repli ou pas encore de météo). */
    @Volatile private var fetchedAt: Pair<Double, Double>? = null
    /** Requête en cours ou déjà demandée : évite de réveiller la boucle à chaque relevé GPS. */
    @Volatile private var busy = false

    fun refreshNow(context: Context) {
        scope.launch {
            val ok = try { refresh(context.applicationContext) } catch (e: Exception) {
                Log.w(TAG, "échec : ${e.javaClass.simpleName} ${e.message}"); false
            }
            _failed.value = !ok
            if (!ok) { lastFailed = true; failingSince = failingSince.takeIf { it != 0L } ?: System.currentTimeMillis(); wake.trySend(Unit) }
        }
    }

    // Pas de test préalable de connectivité : sur la voiture, internet passe par l'interface
    // Ethernet de la T-box, et la requête elle-même est le seul test fiable.
    private fun refresh(context: Context): Boolean {
        busy = true
        try { return doRefresh(context) } finally { busy = false }
    }

    private fun doRefresh(context: Context): Boolean {
        Log.i(TAG, "requête Open-Meteo (internet : ${StatusMonitor.hasInternet(context)})")
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
        )
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        val body = try {
            if (conn.responseCode != 200) { Log.w(TAG, "HTTP ${conn.responseCode}"); return false }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
        _weather.value = parse(JSONObject(body), place)
        Log.i(TAG, "mise à jour : ${_weather.value?.tempC}° à $place")
        fetchedAt = loc
        return true
    }

    /**
     * Nom de la ville approximative (OpenStreetMap Nominatim, géocodage inverse), mis en cache :
     * nouvelle requête seulement après 5 km ou au changement de langue (issue #6 : afficher la ville
     * plutôt que « Position actuelle »). Null hors ligne ou en cas d'échec.
     */
    private fun cityName(context: Context, lat: Double, lon: Double): String? {
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
            Log.w(TAG, "ville : ${e.message}"); cached
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
        // Ciel dégagé la nuit : lune et « Dégagé » au lieu du soleil (issue #7)
        fun night(code: Int, isDay: Int) = if (isDay == 0 && code <= 1) NIGHT_CLEAR else code
        var start = 0
        for (i in 0 until hTimes.length()) if (hTimes.getString(i) <= curTime) start = i
        val hours = (1..5).mapNotNull { k ->
            val i = start + k
            if (i >= hTimes.length()) null
            else Hour(hourLabel(hTimes.getString(i)), night(hCodes.getInt(i), hDay?.optInt(i, 1) ?: 1), Math.round(hTemps.getDouble(i)).toInt())
        }
        val daily = json.getJSONObject("daily")
        val dTimes = daily.getJSONArray("time")
        val days = (1 until minOf(4, dTimes.length())).map { i ->
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

    /** « 14h » en français, « 14:00 » ailleurs. */
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

    /** Code interne : ciel dégagé de nuit (codes WMO 0 et 1 quand il fait nuit). */
    const val NIGHT_CLEAR = 100

    /** Codes WMO → libellé français. */
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

    /** Couleur de l'icône : soleil orange, pluie cyan, orage orange, neige et nuages en clair. */
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
