package app.mg4dash.chrono

/**
 * The chrono's maths, pure (no Android): exact crossing instants from the speed samples, the start instant, and the
 * targets in km/h or mph. Speeds in km/h, times in seconds.
 *
 * The car's speed changes in steps (a new value every ~100 ms or so); a sample is kept only when the value changes, at
 * the time it was first seen. A threshold falls between two samples: the speed is fitted on the samples just before it
 * (a curve through the last three, so the acceleration is taken into account) and the instant it reaches the threshold
 * is solved on that curve. The start is extrapolated the same way, back to 0 km/h from the first moving samples.
 */
object ChronoMath {
    /** A speed sample: [t] in seconds (elapsedRealtime), [v] in km/h. */
    data class Sample(val t: Double, val v: Double)

    /** km/h per mph. */
    const val MPH = 1.609344

    /** What is measured: a standing start (0-50 then 0-100, or 0-30 then 0-60 mph) or the reprise (80-120, or 50-70 mph). */
    enum class Kind { STANDING, ROLLING }

    /** The targets in km/h: (start, split, end); the split is null for the reprise. */
    data class Targets(val from: Double, val split: Double?, val to: Double)

    fun targets(kind: Kind, mph: Boolean): Targets = when (kind) {
        Kind.STANDING -> if (mph) Targets(0.0, 30 * MPH, 60 * MPH) else Targets(0.0, 50.0, 100.0)
        Kind.ROLLING -> if (mph) Targets(50 * MPH, null, 70 * MPH) else Targets(80.0, null, 120.0)
    }

    /**
     * The instant the speed first reaches [threshold], or null when it never does. Between the sample before and the
     * one at or over it, the speed follows the curve through the last three samples (two when there are only two).
     */
    fun crossing(s: List<Sample>, threshold: Double): Double? {
        val i = s.indexOfFirst { it.v >= threshold }
        if (i < 0) return null
        if (i == 0) return s[0].t
        val a = s[i - 1]; val b = s[i]
        if (b.t <= a.t) return b.t
        val linear = a.t + (threshold - a.v) / (b.v - a.v) * (b.t - a.t)
        if (i < 2) return linear
        val p = s[i - 2]
        if (a.t <= p.t || a.v < p.v) return linear
        // Lagrange curve through p, a, b: exact at the three samples, so it goes from below to over the threshold
        fun v(t: Double) = p.v * (t - a.t) * (t - b.t) / ((p.t - a.t) * (p.t - b.t)) +
            a.v * (t - p.t) * (t - b.t) / ((a.t - p.t) * (a.t - b.t)) +
            b.v * (t - p.t) * (t - a.t) / ((b.t - p.t) * (b.t - a.t))
        var lo = a.t; var hi = b.t
        if (v(lo) > threshold || v(hi) < threshold) return linear
        repeat(50) { val m = (lo + hi) / 2; if (v(m) < threshold) lo = m else hi = m }
        return (lo + hi) / 2
    }

    /**
     * The standing start: the instant the car left 0, from the first moving samples (their acceleration taken back to
     * 0 km/h), never before [lastZero] (the last time 0 was read) nor after the first moving sample.
     */
    fun start(s: List<Sample>, lastZero: Double?): Double? {
        val k = s.indexOfFirst { it.v > 0.0 }
        if (k < 0) return null
        val first = s[k]
        val next = s.drop(k + 1).firstOrNull { it.v > first.v && it.t > first.t }
        val back = if (next != null) {
            val acc = (next.v - first.v) / (next.t - first.t)
            first.t - first.v / acc
        } else first.t
        return back.coerceIn(lastZero ?: Double.NEGATIVE_INFINITY, first.t)
    }

    /** Distance (m) covered along the samples (speed held between two samples, trapezes). */
    fun distance(s: List<Sample>, from: Double, to: Double): Double {
        var m = 0.0
        for (i in 1 until s.size) {
            val a = s[i - 1]; val b = s[i]
            val t0 = maxOf(a.t, from); val t1 = minOf(b.t, to)
            if (t1 <= t0) continue
            m += (a.v + b.v) / 2 / 3.6 * (t1 - t0)
        }
        return m
    }

    /** The speed at [t], between the samples (straight line), for the trace and the ghost. */
    fun speedAt(s: List<Sample>, t: Double): Double {
        if (s.isEmpty()) return 0.0
        if (t <= s.first().t) return if (t < s.first().t) 0.0 else s.first().v
        for (i in 1 until s.size) {
            val a = s[i - 1]; val b = s[i]
            if (t <= b.t) return if (b.t <= a.t) b.v else a.v + (b.v - a.v) * (t - a.t) / (b.t - a.t)
        }
        return s.last().v
    }

    /** The trace kept with a run: the speed every [step] s from [from] to [to]. */
    fun trace(s: List<Sample>, from: Double, to: Double, step: Double = TRACE_STEP): FloatArray {
        val n = ((to - from) / step).toInt() + 1
        return FloatArray(n.coerceIn(1, 600)) { speedAt(s, from + it * step).toFloat() }
    }

    const val TRACE_STEP = 0.1

    /** The time a [trace] took to reach [v] km/h (interpolated), or null when it never did. */
    fun traceTimeAt(trace: FloatArray, v: Float, step: Double = TRACE_STEP): Double? {
        val i = trace.indexOfFirst { it >= v }
        if (i < 0) return null
        if (i == 0) return 0.0
        val a = trace[i - 1]; val b = trace[i]
        return (i - 1 + if (b > a) ((v - a) / (b - a)).toDouble() else 1.0) * step
    }

    /** The speed of a [trace] at [t] s (for the ghost), the last one after its end. */
    fun traceSpeedAt(trace: FloatArray, t: Double, step: Double = TRACE_STEP): Float {
        if (trace.isEmpty() || t <= 0) return 0f
        val x = t / step
        val i = x.toInt()
        if (i >= trace.lastIndex) return trace.last()
        return (trace[i] + (trace[i + 1] - trace[i]) * (x - i)).toFloat()
    }

    /** The power curve kept: the highest kW seen at each whole km/h from 0 to [maxKmh]; NaN where none was read. */
    fun powerCurve(points: List<Pair<Double, Double>>, maxKmh: Int): FloatArray {
        val out = FloatArray(maxKmh + 1) { Float.NaN }
        for ((v, kw) in points) {
            val i = Math.round(v).toInt()
            if (i !in 0..maxKmh) continue
            if (out[i].isNaN() || kw > out[i]) out[i] = kw.toFloat()
        }
        return out
    }
}
