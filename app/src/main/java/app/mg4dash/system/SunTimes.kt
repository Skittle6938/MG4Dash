package app.mg4dash.system

import android.content.Context
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Sunrise and sunset at the car's position, computed locally (NOAA formulas, within one or
 * two minutes): no network needed. Used by the automatic night mode (#64): at night, even with
 * side lights off, the screen stays dark.
 */
object SunTimes {
    /** Sunrise and sunset (time in ms) of the day of [now] at [lat]/[lon], or null beyond the polar circle. */
    fun of(lat: Double, lon: Double, now: Long = System.currentTimeMillis()): Pair<Long, Long>? {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = now }
        // Local day approximated from longitude (the head unit's time zone can be wrong at wake-up)
        utc.timeInMillis = now + (lon / 15.0 * 3_600_000).toLong()
        val day = utc.get(Calendar.DAY_OF_YEAR)
        utc.set(Calendar.HOUR_OF_DAY, 0); utc.set(Calendar.MINUTE, 0); utc.set(Calendar.SECOND, 0); utc.set(Calendar.MILLISECOND, 0)
        val midnight = utc.timeInMillis
        val g = 2 * Math.PI / 365 * (day - 1)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) - 0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) + 0.000907 * sin(2 * g) -
            0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        val la = Math.toRadians(lat)
        val x = cos(Math.toRadians(90.833)) / (cos(la) * cos(decl)) - tan(la) * tan(decl)
        if (x < -1 || x > 1) return null
        val ha = Math.toDegrees(acos(x))
        val rise = 720 - 4 * (lon + ha) - eqTime   // minutes UTC
        val set = 720 - 4 * (lon - ha) - eqTime
        return (midnight + (rise * 60_000).toLong()) to (midnight + (set * 60_000).toLong())
    }

    /** Last known position (kept for starts without GPS), or null. */
    fun position(context: Context): Pair<Double, Double>? {
        val sp = Prefs(context).raw
        val loc = app.mg4dash.map.LocationRepo.location.value
        if (loc != null) {
            if (kotlin.math.abs(sp.getFloat("sun_lat", 999f) - loc.latitude) > 0.1 || kotlin.math.abs(sp.getFloat("sun_lon", 999f) - loc.longitude) > 0.1)
                sp.edit().putFloat("sun_lat", loc.latitude.toFloat()).putFloat("sun_lon", loc.longitude.toFloat()).apply()
            return loc.latitude to loc.longitude
        }
        val lat = sp.getFloat("sun_lat", 999f); val lon = sp.getFloat("sun_lon", 999f)
        return if (lat < 900f && lon < 900f) lat.toDouble() to lon.toDouble() else null
    }

    /** Night at the car's position (before sunrise or after sunset); false if position unknown. */
    fun isNight(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val (lat, lon) = position(context) ?: return false
        val t = of(lat, lon, now) ?: return false
        return now < t.first || now > t.second
    }
}
