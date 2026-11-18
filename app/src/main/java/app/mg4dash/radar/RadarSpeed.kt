package app.mg4dash.radar

/**
 * The speed the alerter shows and checks (Marc, 18/11: "the car's speed, set right now and then on the GPS when both
 * are steady for a few seconds"): the car's own speed, read four times a second, times a factor learnt from the GPS.
 * The car's speed comes at once and never stops in a tunnel; the GPS gives the true speed, the one the cameras measure
 * (the dashboard's reads a few % high).
 *
 * The factor is learnt only on steady stretches: [WINDOW_MS] at least of GPS fixes, all above [MIN_KMH], both speeds
 * changing less than [MAX_ACCEL] m/s², a precise fix, a steady heading. Each stretch gives the median of its GPS / car
 * ratios, folded into the factor ([ALPHA]); the factor stays within [MIN_K]..[MAX_K], and is used once [MIN_WINDOWS]
 * stretches are in (1 before). Pure logic: the caller keeps the factor between runs.
 */
class RadarSpeed(k: Float = 1f, windows: Int = 0) {

    /** The factor learnt (GPS / car), and how many stretches it comes from. */
    var k: Float = k.coerceIn(MIN_K, MAX_K); private set
    var windows: Int = windows; private set

    /** The factor in use: 1 until enough stretches are in. */
    val used: Float get() = if (windows >= MIN_WINDOWS) k else 1f

    private class Sample(val t: Long, val car: Float, val gps: Float, val bearing: Float?)
    private val window = ArrayList<Sample>()

    /** The speed to show and check: the car's times the factor, else the GPS's, else none. */
    fun shown(carKmh: Float?, gpsKmh: Float?): Float? = carKmh?.let { it * used } ?: gpsKmh

    /**
     * A GPS fix at [t] ms with the car's speed read at the same time. [accuracy] (m) and [speedAccuracy] (m/s) as the
     * fix gives them, null when unknown. Returns true when a stretch was folded into the factor.
     */
    fun gps(t: Long, carKmh: Float?, gpsKmh: Float?, accuracy: Float?, speedAccuracy: Float?, bearing: Float?): Boolean {
        val ok = carKmh != null && gpsKmh != null && carKmh >= MIN_KMH && gpsKmh >= MIN_KMH &&
            accuracy != null && accuracy <= MAX_ACCURACY_M && (speedAccuracy == null || speedAccuracy <= MAX_SPEED_ACCURACY)
        if (!ok) { window.clear(); return false }
        val s = Sample(t, carKmh!!, gpsKmh!!, bearing)
        val p = window.lastOrNull()
        if (p != null && !steady(p, s)) window.clear()
        window += s
        if (window.last().t - window.first().t < WINDOW_MS) return false
        val ratio = window.map { it.gps / it.car }.sorted().let { r -> if (r.size % 2 == 1) r[r.size / 2] else (r[r.size / 2 - 1] + r[r.size / 2]) / 2 }
        window.clear()
        // A ratio far off (a wrong fix, wheels spinning) is not taken at all
        if (ratio !in MIN_K - 0.05f..MAX_K + 0.05f) return false
        k = (if (windows == 0) ratio else k + ALPHA * (ratio - k)).coerceIn(MIN_K, MAX_K)
        windows++
        return true
    }

    /** Two fixes in a row on a steady stretch: close in time, both speeds and the heading barely changing. */
    private fun steady(a: Sample, b: Sample): Boolean {
        val dt = (b.t - a.t) / 1000f
        if (dt <= 0f || dt > MAX_GAP_S) return false
        val maxDv = MAX_ACCEL * 3.6f * dt
        if (Math.abs(b.car - a.car) > maxDv || Math.abs(b.gps - a.gps) > maxDv) return false
        if (a.bearing != null && b.bearing != null) {
            val d = Math.abs(((b.bearing - a.bearing + 540f) % 360f) - 180f)
            if (d > MAX_TURN_DEG_S * dt) return false
        }
        return true
    }

    companion object {
        const val WINDOW_MS = 5_000L
        const val MIN_KMH = 30f
        /** m/s² */
        const val MAX_ACCEL = 0.5f
        const val MAX_ACCURACY_M = 15f
        /** m/s */
        const val MAX_SPEED_ACCURACY = 1f
        const val MAX_TURN_DEG_S = 5f
        const val MAX_GAP_S = 2.5f
        const val ALPHA = 0.2f
        const val MIN_K = 0.90f
        const val MAX_K = 1.05f
        const val MIN_WINDOWS = 3

        /** How often the car's speed is read while the alerter runs and the car moves (or has just stopped). */
        const val TICK_MS = 250L
        /** Parked this long: read once a second only, enough to see it move off. */
        const val PARKED_MS = 60_000L
        const val PARKED_TICK_MS = 1_000L
    }
}
