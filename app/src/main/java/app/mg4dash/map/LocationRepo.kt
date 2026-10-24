package app.mg4dash.map

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import app.mg4dash.BuildConfig
import app.mg4dash.system.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Position du véhicule. La MG4 expose un fournisseur « fused » précis (vérifié sur la voiture
 * le 2026-10-13), avec vitesse et cap ; repli sur « gps ».
 */
object LocationRepo {
    private const val TAG = "Location"
    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location

    @SuppressLint("MissingPermission")
    private var appContext: Context? = null

    fun start(context: Context) {
        appContext = context.applicationContext
        if (BuildConfig.MOCK) {
            _location.value = Location("mock").apply {
                latitude = 48.8584; longitude = 2.2945; bearing = 120f; speed = 0f; altitude = 35.0
            }
            return
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm.allProviders.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }?.let { _location.value = it }
        } catch (_: Exception) {}
        subscribe(lm)
        // Constaté sur la voiture : au démarrage, « fused » n'est pas toujours encore déclaré et
        // l'abonnement partait sur « gps », qui ne fournit rien. On s'abonne donc à tous les
        // fournisseurs, et on vérifie régulièrement qu'il n'en manque pas.
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed(object : Runnable {
            override fun run() { subscribe(lm); handler.postDelayed(this, 30_000) }
        }, 30_000)
    }

    private val subscribed = mutableSetOf<String>()

    @SuppressLint("MissingPermission")
    private fun subscribe(lm: LocationManager) {
        listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { it in lm.allProviders && it !in subscribed }
            .forEach { p ->
                try {
                    lm.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper())
                    subscribed += p
                    Log.i(TAG, "location updates via \"$p\"")
                } catch (e: Exception) {
                    Log.w(TAG, "\"$p\" unavailable: ${e.message}")
                }
            }
    }

    private var lastLog = 0L

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val cur = _location.value
            // Garde la position la plus récente, et préfère « fused » (le plus précis) quand il émet.
            if (cur != null && location.provider != "fused" && cur.provider == "fused" &&
                location.time - cur.time < 5_000) return
            _location.value = location
            appContext?.let { app.mg4dash.weather.WeatherRepo.onLocation(it) }
            if (System.currentTimeMillis() - lastLog > 60_000) {
                lastLog = System.currentTimeMillis()
                Log.i(TAG, "location received (${location.provider}, ${Math.round(location.speed * 3.6f)} km/h)")
            }
        }
        @Deprecated("API 28")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    fun cardinal(bearing: Float): String {
        val dirs = app.mg4dash.Tr.s(app.mg4dash.R.string.cardinals).split(",")
        return dirs[(((bearing % 360) + 360 + 22.5f) / 45f).toInt() % 8]
    }
}
