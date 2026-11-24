package app.mg4dash.chrono

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import app.mg4dash.R
import app.mg4dash.system.Units
import app.mg4dash.ui.Themes
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The chrono's drawing, shared by the card, the full screen and the window: the dial, chips, the bars of the last
 * runs, the progress track, the power curve and the small icons. Only the theme's colours: the accent ([accent]),
 * orange for Sport and the records, green when ahead.
 */
class ChronoDraw(val c: Context) {
    val d = c.resources.displayMetrics.density
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val light: Typeface = Themes.valueFont(c)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val path = Path()

    val accent get() = Themes.select(c)
    val orange get() = Themes.color(c, R.color.accent_heat)
    val green get() = Themes.energy(c)
    val value get() = Themes.color(c, R.color.text_value)
    val sub get() = Themes.color(c, R.color.text_sub)
    val title get() = Themes.color(c, R.color.text_title)
    val border get() = Themes.color(c, R.color.panel_border)
    val idle get() = Themes.color(c, R.color.button_idle)

    fun alpha(color: Int, a: Float) = (color and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)

    val mph get() = Units.miles(c)
    val unit get() = Units.speedUnit(c)

    /** km/h shown in the driver's unit. */
    fun speed(kmh: Double): Int = Math.round(if (mph) kmh / ChronoMath.MPH else kmh).toInt()

    /** "7,48" (two decimals, the driver's decimal sign). */
    fun time(s: Number?): String = s?.let { String.format(Locale.getDefault(), "%.2f", it.toDouble()) } ?: "—"

    /** "−0,03" / "+0,21". */
    fun signed(s: Double): String = (if (s < 0) "−" else "+") + String.format(Locale.getDefault(), "%.2f", kotlin.math.abs(s))

    /** "0-100 km/h", "0-60 mph", "80-120 km/h", or the split "0-50 km/h". */
    fun label(kind: ChronoMath.Kind, split: Boolean = false): String {
        val t = ChronoMath.targets(kind, mph)
        val to = if (split) t.split ?: t.to else t.to
        return "${speed(t.from)}-${speed(to)} $unit"
    }

    fun date(at: Long): String {
        val today = java.util.Calendar.getInstance()
        val then = java.util.Calendar.getInstance().apply { timeInMillis = at }
        return if (today.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) && today.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR))
            android.text.format.DateFormat.getTimeFormat(c).format(java.util.Date(at))
        else java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "d MMM"), Locale.getDefault()).format(java.util.Date(at))
    }

    fun climb(m: Float?): String = m?.let { if (kotlin.math.abs(it) < 0.5f) "0 m" else (if (it > 0) "+" else "−") + String.format(Locale.getDefault(), "%.0f m", kotlin.math.abs(it)) } ?: "—"
    fun temp(t: Float?): String = t?.let { "${Math.round(it)}°" } ?: "—"
    fun kw(k: Float?): String = k?.let { "${Math.round(it)} kW" } ?: "—"
    fun dist(m: Float): String = if (mph) String.format(Locale.getDefault(), "%.0f yd", m / 0.9144) else String.format(Locale.getDefault(), "%.0f m", m)

    fun text(cv: Canvas, s: String, x: Float, y: Float, sp: Float, color: Int, face: Typeface = Typeface.DEFAULT, align: Paint.Align = Paint.Align.LEFT, spacing: Float = 0f) {
        p.style = Paint.Style.FILL; p.typeface = face; p.textSize = sp * d; p.color = color; p.textAlign = align; p.letterSpacing = spacing
        cv.drawText(s, x, y, p)
        p.letterSpacing = 0f
    }

    fun width(s: String, sp: Float, face: Typeface = Typeface.DEFAULT, spacing: Float = 0f): Float {
        p.typeface = face; p.textSize = sp * d; p.letterSpacing = spacing
        return p.measureText(s).also { p.letterSpacing = 0f }
    }

    /** A pill with [s]: outlined and tinted in [color]; returns its width. */
    fun chip(cv: Canvas, s: String, x: Float, cy: Float, sp: Float, color: Int, filled: Float = 0.16f, alignRight: Boolean = false): Float {
        val w = width(s, sp, medium) + sp * d * 1.3f
        val h = sp * d * 1.75f
        val left = if (alignRight) x - w else x
        val r = RectF(left, cy - h / 2, left + w, cy + h / 2)
        p.style = Paint.Style.FILL; p.color = alpha(color, filled); cv.drawRoundRect(r, h / 2, h / 2, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.3f * d; p.color = alpha(color, 0.8f); cv.drawRoundRect(r, h / 2, h / 2, p)
        text(cv, s, r.centerX(), cy + sp * d * 0.36f, sp, color, medium, Paint.Align.CENTER)
        return w
    }

    /**
     * The stopwatch dial: crown on top, 60 ticks around a ring; [arc] 0..1 of the ring filled in the accent (the speed
     * while measuring); [pulse] 0..1 the armed halo; [top] / [bottom] the texts in the middle; [icon] a flag over [top].
     */
    fun dial(cv: Canvas, cx: Float, cy: Float, r: Float, on: Boolean, arc: Float?, pulse: Float?, top: String, bottom: String?, icon: Boolean, topColor: Int = accent) {
        val ring = r * 0.82f
        // Halo when armed
        if (pulse != null) {
            p.style = Paint.Style.FILL
            p.color = alpha(accent, 0.22f * (1 - pulse)); cv.drawCircle(cx, cy, ring + r * 0.1f + pulse * r * 0.16f, p)
        }
        // Crown
        p.style = Paint.Style.FILL; p.color = if (on) accent else alpha(value, 0.85f)
        val cw = r * 0.26f; val ch = r * 0.12f
        cv.drawRoundRect(RectF(cx - cw / 2, cy - r - ch * 0.4f, cx + cw / 2, cy - r + ch * 0.55f), ch / 3, ch / 3, p)
        cv.drawRect(cx - cw * 0.17f, cy - r + ch * 0.4f, cx + cw * 0.17f, cy - ring + 2 * d, p)
        // Face
        p.color = alpha(if (on) accent else idle, if (on) 0.14f else 1f); cv.drawCircle(cx, cy, ring, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = max(2f * d, r * 0.045f)
        p.color = if (arc != null) alpha(accent, 0.25f) else if (on) alpha(accent, if (pulse != null) 0.55f + 0.45f * (1 - pulse) else 1f) else border
        cv.drawCircle(cx, cy, ring, p)
        // Ticks
        for (i in 0 until 60) {
            val a = Math.toRadians(i * 6.0 - 90).toFloat()
            val long = i % 5 == 0
            val r0 = ring * (if (long) 0.8f else 0.86f); val r1 = ring * 0.92f
            p.strokeWidth = if (long) 1.8f * d else 1f * d
            p.color = alpha(if (on) accent else sub, if (long) 0.9f else 0.45f)
            cv.drawLine(cx + cos(a) * r0, cy + sin(a) * r0, cx + cos(a) * r1, cy + sin(a) * r1, p)
        }
        // Speed arc
        if (arc != null) {
            p.strokeWidth = r * 0.07f; p.strokeCap = Paint.Cap.ROUND; p.color = accent
            val o = RectF(cx - ring, cy - ring, cx + ring, cy + ring)
            cv.drawArc(o, -90f, 360f * arc.coerceIn(0f, 1f), false, p)
            p.strokeCap = Paint.Cap.BUTT
        }
        p.style = Paint.Style.FILL
        if (icon) {
            flag(cv, cx, cy - ring * 0.2f, ring * 0.36f, topColor)
            text(cv, top, cx, cy + ring * 0.42f, ring * 0.2f / d, topColor, medium, Paint.Align.CENTER, 0.1f)
        } else {
            text(cv, top, cx, cy + (if (bottom != null) ring * 0.06f else ring * 0.12f), ring * (if (top.length > 4) 0.22f else 0.34f) / d, topColor, medium, Paint.Align.CENTER, 0.06f)
            bottom?.let { text(cv, it, cx, cy + ring * 0.42f, ring * 0.16f / d, sub, Typeface.DEFAULT, Paint.Align.CENTER) }
        }
    }

    /** A chequered flag of [size], centred on [cx], [cy]. */
    fun flag(cv: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        val w = size; val h = size * 0.66f
        val left = cx - w / 2 + size * 0.06f; val top = cy - h / 2
        p.style = Paint.Style.FILL; p.color = color
        cv.drawRect(left - size * 0.09f, top, left - size * 0.03f, top + size * 1.05f, p)
        val n = 4; val m = 3
        for (i in 0 until n) for (j in 0 until m) {
            p.color = if ((i + j) % 2 == 0) color else alpha(color, 0.25f)
            cv.drawRect(left + i * w / n, top + j * h / m, left + (i + 1) * w / n, top + (j + 1) * h / m, p)
        }
    }

    /** A small cup (the records). */
    fun trophy(cv: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        p.style = Paint.Style.FILL; p.color = color
        path.reset()
        path.moveTo(cx - size * 0.38f, cy - size * 0.45f); path.lineTo(cx + size * 0.38f, cy - size * 0.45f)
        path.quadTo(cx + size * 0.36f, cy + size * 0.12f, cx, cy + size * 0.16f)
        path.quadTo(cx - size * 0.36f, cy + size * 0.12f, cx - size * 0.38f, cy - size * 0.45f); path.close()
        cv.drawPath(path, p)
        cv.drawRect(cx - size * 0.06f, cy + size * 0.1f, cx + size * 0.06f, cy + size * 0.32f, p)
        cv.drawRoundRect(RectF(cx - size * 0.24f, cy + size * 0.3f, cx + size * 0.24f, cy + size * 0.42f), size * 0.04f, size * 0.04f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = size * 0.08f
        cv.drawArc(RectF(cx - size * 0.56f, cy - size * 0.4f, cx - size * 0.2f, cy - size * 0.04f), 90f, 180f, false, p)
        cv.drawArc(RectF(cx + size * 0.2f, cy - size * 0.4f, cx + size * 0.56f, cy - size * 0.04f), -90f, 180f, false, p)
        p.style = Paint.Style.FILL
    }

    /** Small line icons before the conditions: battery, thermometer, slope, bolt. */
    enum class Icon { BATTERY, TEMP, CLIMB, BOLT, DIST }

    fun icon(cv: Canvas, which: Icon, cx: Float, cy: Float, size: Float, color: Int) {
        p.color = color; p.strokeWidth = max(1.4f * d, size * 0.1f); p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        val s = size / 2
        when (which) {
            Icon.BATTERY -> {
                p.style = Paint.Style.STROKE; cv.drawRoundRect(RectF(cx - s, cy - s * 0.5f, cx + s * 0.8f, cy + s * 0.5f), s * 0.15f, s * 0.15f, p)
                p.style = Paint.Style.FILL; cv.drawRect(cx + s * 0.8f, cy - s * 0.2f, cx + s, cy + s * 0.2f, p)
                cv.drawRect(cx - s * 0.75f, cy - s * 0.27f, cx + s * 0.2f, cy + s * 0.27f, p)
            }
            Icon.TEMP -> {
                p.style = Paint.Style.STROKE; cv.drawLine(cx, cy - s, cx, cy + s * 0.3f, p)
                p.style = Paint.Style.FILL; cv.drawCircle(cx, cy + s * 0.55f, s * 0.38f, p)
            }
            Icon.CLIMB -> {
                p.style = Paint.Style.STROKE
                path.reset(); path.moveTo(cx - s, cy + s * 0.7f); path.lineTo(cx - s * 0.2f, cy - s * 0.3f); path.lineTo(cx + s * 0.2f, cy + s * 0.15f)
                path.lineTo(cx + s * 0.6f, cy - s * 0.7f); path.lineTo(cx + s, cy + s * 0.7f); path.close(); cv.drawPath(path, p)
            }
            Icon.BOLT -> {
                p.style = Paint.Style.FILL
                path.reset(); path.moveTo(cx + s * 0.2f, cy - s); path.lineTo(cx - s * 0.6f, cy + s * 0.15f); path.lineTo(cx - s * 0.05f, cy + s * 0.15f)
                path.lineTo(cx - s * 0.25f, cy + s); path.lineTo(cx + s * 0.6f, cy - s * 0.2f); path.lineTo(cx + s * 0.05f, cy - s * 0.2f); path.close(); cv.drawPath(path, p)
            }
            Icon.DIST -> {
                p.style = Paint.Style.STROKE; cv.drawLine(cx - s, cy, cx + s, cy, p)
                cv.drawLine(cx - s, cy - s * 0.4f, cx - s, cy + s * 0.4f, p); cv.drawLine(cx + s, cy - s * 0.4f, cx + s, cy + s * 0.4f, p)
            }
        }
        p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
    }

    /** Icons and texts on one line from [x]; returns where it ends. */
    fun conditions(cv: Canvas, items: List<Pair<Icon, String>>, x: Float, cy: Float, sp: Float, maxRight: Float): Float {
        var cx = x
        for ((ic, s) in items) {
            val need = sp * d * 1.2f + width(s, sp) + sp * d * 1.1f
            if (cx + need > maxRight) break
            icon(cv, ic, cx + sp * d * 0.5f, cy - sp * d * 0.32f, sp * d * 0.9f, sub)
            text(cv, s, cx + sp * d * 1.2f, cy, sp, value)
            cx += need
        }
        return cx
    }

    /** The last runs' bars, oldest on the left: taller is faster; the record in orange, the last in the accent. */
    fun bars(cv: Canvas, r: RectF, runs: List<ChronoRunData>, recordAt: Long?) {
        val n = ChronoHistory.KEEP
        val gap = r.width() / n * 0.28f
        val bw = (r.width() - gap * (n - 1)) / n
        val times = runs.map { it.full }
        val lo = (times.minOrNull() ?: 0f); val hi = (times.maxOrNull() ?: 1f)
        for (i in 0 until n) {
            val x = r.left + i * (bw + gap)
            val run = runs.getOrNull(i - (n - runs.size))
            p.style = Paint.Style.FILL
            if (run == null) { p.color = alpha(sub, 0.12f); cv.drawRoundRect(RectF(x, r.bottom - r.height() * 0.12f, x + bw, r.bottom), bw / 4, bw / 4, p); continue }
            val f = if (hi - lo < 0.01f) 0.75f else 0.3f + 0.7f * (hi - run.full) / (hi - lo)
            p.color = when { run.at == recordAt -> orange; run === runs.last() -> accent; else -> alpha(sub, 0.45f) }
            cv.drawRoundRect(RectF(x, r.bottom - r.height() * f, x + bw, r.bottom), bw / 4, bw / 4, p)
        }
    }

    /** The progress track: filled to [progress], a mark at [split], the record's ghost in orange. */
    fun track(cv: Canvas, r: RectF, progress: Float, split: Float?, ghost: Float?, splitLabel: String?) {
        val h = r.height()
        p.style = Paint.Style.FILL; p.color = alpha(sub, 0.18f); cv.drawRoundRect(r, h / 2, h / 2, p)
        p.color = accent; cv.drawRoundRect(RectF(r.left, r.top, r.left + max(h, r.width() * progress.coerceIn(0f, 1f)), r.bottom), h / 2, h / 2, p)
        split?.let { s ->
            val x = r.left + r.width() * s
            p.color = alpha(value, 0.7f); cv.drawRect(x - d, r.top - h * 0.6f, x + d, r.bottom + h * 0.6f, p)
            splitLabel?.let { text(cv, it, x, r.top - h * 1.2f, max(11f, h / d * 1.1f), sub, Typeface.DEFAULT, Paint.Align.CENTER) }
        }
        ghost?.let { g ->
            val x = r.left + r.width() * g.coerceIn(0f, 1f)
            p.style = Paint.Style.FILL; p.color = orange
            path.reset(); path.moveTo(x, r.bottom + h * 0.4f); path.lineTo(x - h * 0.9f, r.bottom + h * 1.7f); path.lineTo(x + h * 0.9f, r.bottom + h * 1.7f); path.close()
            cv.drawPath(path, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; cv.drawCircle(x, r.centerY(), h * 0.85f, p); p.style = Paint.Style.FILL
        }
    }

    /**
     * Power against speed: [run] solid (green, light area under it) and the [record] dashed in orange; a grid in kW
     * when [axes], the peak's label, a dashed line at the split with its time. [touchV]: a reading at that speed.
     */
    fun curve(cv: Canvas, r: RectF, run: ChronoRunData?, record: ChronoRunData?, axes: Boolean, legend: Boolean, touchV: Float? = null, small: Boolean = false) {
        val t = ChronoMath.targets(run?.kind ?: record?.kind ?: ChronoMath.Kind.STANDING, mph)
        val v0 = t.from.toFloat(); val v1 = t.to.toFloat()
        val all = listOfNotNull(run?.curve, record?.curve).flatMap { it.filter { k -> !k.isNaN() } }
        val top = max(50f, ((all.maxOrNull() ?: 100f) / 25f).let { Math.ceil(it.toDouble()).toFloat() * 25f + 10f })
        val left = if (axes) r.left + 44 * d else r.left
        val bottom = if (axes) r.bottom - 26 * d else r.bottom
        val box = RectF(left, r.top + (if (legend || axes) 22 * d else 0f), r.right, bottom)
        fun x(v: Float) = box.left + (v - v0) / (v1 - v0) * box.width()
        fun y(k: Float) = box.bottom - (k.coerceAtLeast(0f) / top) * box.height()
        if (axes) {
            p.strokeWidth = 1f * d; p.style = Paint.Style.STROKE
            var k = 0f
            while (k <= top) {
                p.color = alpha(border, if (k == 0f) 1f else 0.6f); cv.drawLine(box.left, y(k), box.right, y(k), p)
                text(cv, "${k.toInt()}", box.left - 8 * d, y(k) + 5 * d, 13f, sub, align = Paint.Align.RIGHT)
                p.style = Paint.Style.STROKE
                k += if (top > 150) 50f else 25f
            }
            text(cv, "kW", box.left - 8 * d, box.top - 4 * d, 12f, sub, align = Paint.Align.RIGHT)
            val step = if (mph) 10 else 25
            var v = Math.ceil(v0 / (if (mph) ChronoMath.MPH else 1.0) / step).toInt() * step
            while (true) {
                val kmh = (if (mph) v * ChronoMath.MPH else v.toDouble()).toFloat()
                if (kmh > v1 + 0.5f) break
                text(cv, if (kmh + 0.5f >= v1) "$v $unit" else "$v", x(kmh), box.bottom + 20 * d, 13f, sub, align = if (kmh + 0.5f >= v1) Paint.Align.RIGHT else Paint.Align.CENTER)
                v += step
            }
        }
        // The split line
        if (axes && !small && run?.split != null && t.split != null) {
            val sx = x(t.split.toFloat())
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f * d; p.color = alpha(sub, 0.6f); p.pathEffect = DashPathEffect(floatArrayOf(4 * d, 5 * d), 0f)
            cv.drawLine(sx, box.top, sx, box.bottom, p); p.pathEffect = null
            text(cv, "${speed(t.split)} $unit · ${time(run.split)} s", sx + 6 * d, box.top + 16 * d, 13f, sub)
        }
        fun line(curve: FloatArray): Path? {
            val pts = curve.withIndex().filter { !it.value.isNaN() && it.index >= v0 - 0.5f }
            if (pts.size < 2) return null
            val pa = Path()
            pts.forEachIndexed { i, (v, k) -> if (i == 0) pa.moveTo(x(v.toFloat()), y(k)) else pa.lineTo(x(v.toFloat()), y(k)) }
            return pa
        }
        record?.takeIf { it.at != run?.at }?.let { rec ->
            line(rec.curve)?.let {
                p.style = Paint.Style.STROKE; p.strokeWidth = (if (small) 1.6f else 2.2f) * d; p.color = orange
                p.pathEffect = DashPathEffect(floatArrayOf(7 * d, 6 * d), 0f); cv.drawPath(it, p); p.pathEffect = null
            }
        }
        run?.let { cur ->
            line(cur.curve)?.let { pa ->
                val area = Path(pa)
                val first = cur.curve.withIndex().first { !it.value.isNaN() && it.index >= v0 - 0.5f }.index.toFloat()
                val last = cur.curve.withIndex().last { !it.value.isNaN() }.index.toFloat()
                area.lineTo(x(last), box.bottom); area.lineTo(x(first), box.bottom); area.close()
                p.style = Paint.Style.FILL; p.color = alpha(green, 0.14f); cv.drawPath(area, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = (if (small) 2.2f else 3f) * d; p.strokeJoin = Paint.Join.ROUND; p.color = green
                cv.drawPath(pa, p)
            }
            if (axes) cur.maxKw?.let { k ->
                val v = cur.maxKwAt ?: return@let
                p.style = Paint.Style.FILL; p.color = green; cv.drawCircle(x(v), y(k), 5 * d, p)
                val s = c.getString(R.string.ch_peak_at, "${Math.round(k)}", "${speed(v.toDouble())} $unit")
                val right = x(v) + width(s, 14f) + 16 * d > box.right
                // Under the point when it is near the top (the legend is there)
                val below = y(k) - box.top < 30 * d
                text(cv, s, x(v) + (if (right) -10 else 10) * d, y(k) + (if (below) 24 else -10) * d, 14f, value, medium, if (right) Paint.Align.RIGHT else Paint.Align.LEFT)
            }
        }
        if (legend) {
            var lx = box.right
            val ly = r.top + 4 * d
            fun item(label: String, color: Int, dashed: Boolean) {
                val w = width(label, 13f)
                lx -= w
                text(cv, label, lx, ly + 5 * d, 13f, sub)
                p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f * d; p.color = color
                if (dashed) p.pathEffect = DashPathEffect(floatArrayOf(5 * d, 4 * d), 0f)
                cv.drawLine(lx - 30 * d, ly, lx - 8 * d, ly, p); p.pathEffect = null
                lx -= 46 * d
            }
            if (record != null && record.at != run?.at) item(c.getString(R.string.ch_record_run), orange, true)
            if (run != null) item(c.getString(R.string.ch_this_run), green, false)
        }
        // A reading under the finger
        if (touchV != null && run != null) {
            val v = touchV.coerceIn(v0, v1)
            val i = Math.round(v)
            val k = (0..4).asSequence().flatMap { sequenceOf(i - it, i + it) }.firstOrNull { it in run.curve.indices && !run.curve[it].isNaN() }?.let { run.curve[it] }
            val xx = x(v)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.color = value; cv.drawLine(xx, box.top, xx, box.bottom, p)
            val s = "${speed(v.toDouble())} $unit · ${k?.let { "${Math.round(it)} kW" } ?: "—"}"
            val w = width(s, 15f, medium) + 20 * d
            val bx = (xx - w / 2).coerceIn(box.left, box.right - w)
            p.style = Paint.Style.FILL; p.color = idle; cv.drawRoundRect(RectF(bx, box.top + 22 * d, bx + w, box.top + 52 * d), 10 * d, 10 * d, p)
            text(cv, s, bx + w / 2, box.top + 42 * d, 15f, value, medium, Paint.Align.CENTER)
        }
    }

    /** Speed against time, from a run's trace. */
    fun speedTime(cv: Canvas, r: RectF, run: ChronoRunData) {
        val tr = run.trace
        if (tr.size < 2) return
        val tMax = (tr.size - 1) * ChronoMath.TRACE_STEP
        val vMax = max(tr.maxOrNull() ?: 1f, 1f)
        val box = RectF(r.left + 44 * d, r.top + 6 * d, r.right, r.bottom - 24 * d)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f * d; p.color = border
        cv.drawLine(box.left, box.bottom, box.right, box.bottom, p)
        text(cv, "${speed(vMax.toDouble())}", box.left - 8 * d, box.top + 10 * d, 13f, sub, align = Paint.Align.RIGHT)
        text(cv, "0", box.left - 8 * d, box.bottom, 13f, sub, align = Paint.Align.RIGHT)
        text(cv, "0 s", box.left, box.bottom + 19 * d, 13f, sub)
        text(cv, "${time(tMax)} s", box.right, box.bottom + 19 * d, 13f, sub, align = Paint.Align.RIGHT)
        path.reset()
        tr.forEachIndexed { i, v ->
            val x = box.left + (i * ChronoMath.TRACE_STEP / tMax).toFloat() * box.width()
            val y = box.bottom - v / vMax * box.height()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f * d; p.color = accent; cv.drawPath(path, p)
    }

    /** The bigger texts of Grand affichage. */
    fun scaleFor(big: Boolean) = if (big) 1.3f else 1f

    companion object {
        fun min3(a: Float, b: Float, c: Float) = min(a, min(b, c))
    }
}
