package app.mg4dash.brightness

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The light outside, estimated (Marc, 20/11): the car measures none (its light sensor reads 0 lux, its
 * OUTSIDE_AMBIENT_LIGHT stays at 0), so it comes from the sun's height (position and time, computed offline), dimmed by
 * the clouds Open-Meteo forecasts (shortwave radiation, or cloud cover), and the position lights, the car's only real
 * light signal, which win when they say it is dark (tunnel, car park, storm). Pure: no Android, tested on its own.
 */
object LightEstimator {
    /** Lux of a clear sky at noon with the sun at the zenith (illuminance, order of magnitude). */
    const val CLEAR_ZENITH_LUX = 110_000.0
    /** Lux at the horizon (sun at 0°), and at the end of civil twilight (sun at −6°). */
    const val HORIZON_LUX = 400.0
    const val TWILIGHT_END_LUX = 3.0
    /** Lux of the night, below civil twilight. */
    const val NIGHT_LUX = 1.0
    /** The position lights on: the light taken at most this (the default curve's twilight). */
    const val LIGHTS_LUX = 400.0
    /** Clear-sky shortwave radiation at the zenith (W/m²), to turn the forecast into a cloud factor. */
    const val CLEAR_ZENITH_WM2 = 1_000.0

    /** The sun's height above the horizon (degrees) at [lat], [lon] (degrees) and [timeMs] (UTC ms). NOAA's formulas. */
    fun sunElevation(lat: Double, lon: Double, timeMs: Long): Double {
        val jd = timeMs / 86_400_000.0 + 2_440_587.5
        val t = (jd - 2_451_545.0) / 36_525.0
        val l0 = (280.46646 + t * (36_000.76983 + t * 0.0003032)).mod(360.0)
        val m = 357.52911 + t * (35_999.05029 - 0.0001537 * t)
        val mr = Math.toRadians(m)
        val c = sin(mr) * (1.914602 - t * (0.004817 + 0.000014 * t)) + sin(2 * mr) * (0.019993 - 0.000101 * t) + sin(3 * mr) * 0.000289
        val trueLong = l0 + c
        val omega = 125.04 - 1934.136 * t
        val lambda = trueLong - 0.00569 - 0.00478 * sin(Math.toRadians(omega))
        val eps0 = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val eps = eps0 + 0.00256 * cos(Math.toRadians(omega))
        val decl = asin(sin(Math.toRadians(eps)) * sin(Math.toRadians(lambda)))
        // Equation of time (minutes)
        val y = Math.tan(Math.toRadians(eps / 2)).pow(2)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val l0r = Math.toRadians(l0)
        val eqTime = 4 * Math.toDegrees(y * sin(2 * l0r) - 2 * e * sin(mr) + 4 * e * y * sin(mr) * cos(2 * l0r) -
            0.5 * y * y * sin(4 * l0r) - 1.25 * e * e * sin(2 * mr))
        val minutesUtc = (timeMs.mod(86_400_000L)) / 60_000.0
        val trueSolar = (minutesUtc + eqTime + 4 * lon).mod(1440.0)
        val hourAngle = Math.toRadians(if (trueSolar / 4 < 0) trueSolar / 4 + 180 else trueSolar / 4 - 180)
        val latR = Math.toRadians(lat)
        val cosZen = (sin(latR) * sin(decl) + cos(latR) * cos(decl) * cos(hourAngle)).coerceIn(-1.0, 1.0)
        return 90.0 - Math.toDegrees(Math.acos(cosZen))
    }

    /** Lux of a clear sky with the sun at [elevation] degrees: daylight above the horizon, twilight down to −6°, then night. */
    fun clearSky(elevation: Double): Double = when {
        elevation >= 0 -> max(HORIZON_LUX, CLEAR_ZENITH_LUX * sin(Math.toRadians(elevation)))
        elevation >= -6 -> {
            // From 400 lux at the horizon to 3 at −6°, straight on a log scale
            val k = -elevation / 6.0
            10.0.pow(log10(HORIZON_LUX) + k * (log10(TWILIGHT_END_LUX) - log10(HORIZON_LUX)))
        }
        else -> NIGHT_LUX
    }

    /**
     * How much the clouds let through (0.1 to 1): the forecast [radiation] (W/m²) against a clear sky at [elevation],
     * else from the [cloudCover] (%), else 1 (no forecast: clear sky).
     */
    fun cloudFactor(elevation: Double, radiation: Double?, cloudCover: Double?): Double {
        val clear = CLEAR_ZENITH_WM2 * sin(Math.toRadians(elevation))
        if (radiation != null && radiation >= 0 && clear > 50) return (radiation / clear).coerceIn(0.1, 1.0)
        if (cloudCover != null) return (1.0 - 0.75 * (cloudCover.coerceIn(0.0, 100.0) / 100.0).pow(3)).coerceIn(0.1, 1.0)
        return 1.0
    }

    /**
     * The light outside (lux): the sky at [elevation] through the clouds ([radiation], [cloudCover], from the forecast
     * when there is one), at most [LIGHTS_LUX] when [lightsOn]. Lights off at night do not make it brighter.
     */
    fun estimate(elevation: Double, radiation: Double?, cloudCover: Double?, lightsOn: Boolean?): Double {
        var lux = clearSky(elevation)
        if (elevation > 0) lux = max(HORIZON_LUX * 0.5, lux * cloudFactor(elevation, radiation, cloudCover))
        if (lightsOn == true) lux = min(lux, LIGHTS_LUX)
        return lux.coerceAtLeast(NIGHT_LUX)
    }

    /** The light on the curves' scale: log10 of the lux, 0 (1 lux) to 5 (100 000 lux). */
    fun level(lux: Double): Double = log10(lux.coerceIn(1.0, 100_000.0))

    @Suppress("unused") private const val TAU = 2 * PI
}
