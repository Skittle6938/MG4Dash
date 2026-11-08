package app.mg4dash.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import app.mg4dash.R
import java.util.Locale

/** Stacked horizontal bar: energy breakdown (driving, climate, accessories). */
class StackedBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    data class Part(val value: Float, val color: Int)

    var parts: List<Part> = emptyList()
        set(v) { field = v; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val r = resources.displayMetrics.density * 6f
    private val gap = resources.displayMetrics.density * 3f
    private val off = app.mg4dash.ui.Themes.color(context, R.color.gauge_off)

    private val clip = Path()

    /**
     * Power bar style (issue #29): thin rounded rail centred in the view, one gradient
     * part per item, separated by a thin gap.
     */
    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val barH = minOf(14 * d, height.toFloat())
        val top = (height - barH) / 2f
        rect.set(0f, top, width.toFloat(), top + barH)
        paint.shader = null; paint.color = off
        canvas.drawRoundRect(rect, barH / 2, barH / 2, paint)
        val total = parts.sumOf { it.value.toDouble() }.toFloat()
        if (total <= 0f) return
        canvas.save()
        clip.reset(); clip.addRoundRect(rect, barH / 2, barH / 2, Path.Direction.CW); canvas.clipPath(clip)
        var x = 0f
        val visible = parts.filter { it.value > 0f }
        visible.forEach { p ->
            val w = (width - gap * (visible.size - 1)) * p.value / total
            paint.shader = LinearGradient(x, 0f, x + w, 0f, intArrayOf((p.color and 0x00FFFFFF) or (150 shl 24), p.color), null, Shader.TileMode.CLAMP)
            canvas.drawRect(x, top, x + w, top + barH, paint)
            x += w + gap
        }
        canvas.restore(); paint.shader = null
    }
}

/** Breakdown ring, starting at the top; [center]: text in the middle. */
class RingChart @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var parts: List<Pair<Float, Int>> = emptyList()
    private var center = ""
    private val d = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 16f * d; strokeCap = Paint.Cap.BUTT }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = app.mg4dash.ui.Themes.color(context, R.color.text_value); textSize = 17f * d; textAlign = Paint.Align.CENTER
    }
    private val box = RectF()

    fun show(values: List<Pair<Float, Int>>, label: String) {
        if (values == parts && label == center) return
        parts = values; center = label; invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val all = parts.sumOf { it.first.toDouble() }.toFloat()
        val r = minOf(width, height) / 2f - ring.strokeWidth / 2f
        box.set(width / 2f - r, height / 2f - r, width / 2f + r, height / 2f + r)
        if (all <= 0f) {
            ring.color = app.mg4dash.ui.Themes.color(context, R.color.gauge_off)
            canvas.drawArc(box, 0f, 360f, false, ring)
            return
        }
        var start = -90f
        parts.forEach { (v, c) ->
            val sweep = v / all * 360f
            ring.color = c
            canvas.drawArc(box, start, sweep, false, ring)
            start += sweep
        }
        canvas.drawText(center, width / 2f, height / 2f - (text.ascent() + text.descent()) / 2f, text)
    }
}

/** Consumption curve (kWh/100 km) with a gradient area and average line. */
/** Smoothed curve (#44): each point = average of itself and its 2 neighbours on each side (5 points; 3 was too jagged once placed by km). */
fun List<Float>.smoothed(): List<Float> = indices.map { i -> subList(maxOf(0, i - 2), minOf(size, i + 3)).average().toFloat() }

private const val MAX_POINTS = 50

/** Curve slope at each point: zero at peaks and troughs, so as never to overshoot neighbouring points. */
fun curveSlopes(x: FloatArray, y: FloatArray): FloatArray {
    val n = x.size
    val d = FloatArray(n - 1) { i -> (y[i + 1] - y[i]) / (x[i + 1] - x[i]).coerceAtLeast(0.001f) }
    return FloatArray(n) { i ->
        when {
            i == 0 -> d[0]
            i == n - 1 -> d[n - 2]
            d[i - 1] * d[i] <= 0f -> 0f
            else -> {
                val h0 = x[i] - x[i - 1]; val h1 = x[i + 1] - x[i]
                val w1 = 2 * h1 + h0; val w2 = h1 + 2 * h0
                (w1 + w2) / (w1 / d[i - 1] + w2 / d[i])
            }
        }
    }
}

/**
 * At most [max] points, in order: each is the average of an equal slice of the list. A long period
 * (12 months) stays readable and draws quickly; below [max] points, nothing changes.
 */
fun List<Float>.atMost(max: Int): List<Float> =
    if (size <= max) this else List(max) { i -> subList(i * size / max, (i + 1) * size / max).average().toFloat() }

class LineChart @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var values: List<Float> = emptyList()
        set(v) { field = v.atMost(MAX_POINTS); positions = null; layers = null; average = v.average().toFloat(); invalidate() }
    /** Position of each point across the width (0..1), if the curve follows time; otherwise regular points. */
    private var positions: List<Float>? = null
    /** Average of all values received, not of the points kept (rounded slices, not all equal). */
    private var average = 0f
    /** The average's value, as drawn by the dashed line. */
    val averageValue: Float get() = average
    /** Bubble with the average's value at the end of the dashed line; off when the value is shown elsewhere (#44). */
    var averageBubble = true
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Colour of the dashed average line; null: the theme's button colour (#44). */
    var averageColor: Int? = null
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Colour of a dot on the curve's last point (the last km, #44); null: no dot. */
    var lastDotColor: Int? = null
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Height of that dot (the last km's value); null: the curve's last point. */
    var lastDotValue: Float? = null
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Text shown without a curve: 1 point per km, or per trip over 12 months (#44). */
    var emptyText: Int = app.mg4dash.R.string.chart_empty
        set(v) { if (field != v) { field = v; invalidate() } }

    /** Horizontal scale: text under the start and end of the curve ("3 weeks ago", "now"). */
    var startLabel: String? = null
        set(v) { if (field != v) { field = v; invalidate() } }
    var endLabel: String? = null
        set(v) { if (field != v) { field = v; invalidate() } }

    /** Curve placed over time: (position 0..1, value); [mean]: average of all original values. */
    fun showTimeline(points: List<Pair<Float, Float>>, mean: Float) {
        values = points.map { it.second }
        positions = points.map { it.first }
        average = mean
        invalidate()
    }

    /** Stacked layer of a curve: its colour and its value at each point. */
    class Layer(val color: Int, val values: List<Float>)
    private var layers: List<Layer>? = null

    /** Possible tops of the scale, in order: the first that fits, higher values are cut at the last one (#44). */
    var scaleTops: List<Float>? = null

    /** Overlaid and added curves: the last layer gives the total; [points]: positions 0..1, or regular. */
    fun showLayers(points: List<Float>?, stack: List<Layer>, mean: Float) {
        val n = stack.first().values.size
        values = List(n) { i -> stack.sumOf { it.values[i].toDouble() }.toFloat() }
        positions = points
        layers = stack.filter { l -> l.values.any { it > 0.01f } }
        average = mean
        invalidate()
    }

    private val d = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f * d; strokeJoin = Paint.Join.ROUND
        color = app.mg4dash.ui.Themes.energy(context)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint().apply { color = app.mg4dash.ui.Themes.color(context, R.color.panel_border); strokeWidth = 1f }
    private val avgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * d
        color = app.mg4dash.ui.Themes.color(context, R.color.accent_action)   // theme's button colour
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(6 * d, 6 * d), 0f)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = app.mg4dash.ui.Themes.color(context, R.color.text_title); textSize = 14f * d
    }
    private val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = app.mg4dash.ui.Themes.color(context, R.color.text_sub); textSize = 17f * d; textAlign = Paint.Align.CENTER
    }

    /** Top to bottom: colour veil between two cumulative curves, then each item's thin curve. */
    private fun drawLayers(canvas: Canvas, stack: List<Layer>, x: FloatArray, top: Float, bottom: Float, y: (Float) -> Float) {
        val tops = ArrayList<FloatArray>()
        val sum = FloatArray(x.size)
        stack.forEach { l -> for (i in sum.indices) sum[i] += l.values[i]; tops.add(sum.copyOf()) }
        val floor = FloatArray(x.size) { y(0f).coerceAtMost(bottom) }
        for (k in stack.indices.reversed()) {
            val upper = FloatArray(x.size) { y(tops[k][it]) }
            val lower = if (k == 0) floor else FloatArray(x.size) { y(tops[k - 1][it]) }
            val band = curvePath(x, upper)
            band.lineTo(x.last(), lower.last())
            addCurve(band, x.reversedArray(), lower.reversedArray())
            band.close()
            val rgb = stack[k].color and 0xFFFFFF
            fill.shader = LinearGradient(0f, top, 0f, bottom, 0x50000000 or rgb, 0x10000000 or rgb, Shader.TileMode.CLAMP)
            canvas.drawPath(band, fill)
            line.color = stack[k].color
            canvas.drawPath(curvePath(x, upper), line)
        }
    }

    /** Rounded curve through each point, without overshooting its neighbours (monotone cubic interpolation). */
    private fun curvePath(x: FloatArray, y: FloatArray): Path {
        val path = Path().apply { moveTo(x[0], y[0]) }
        addCurve(path, x, y)
        return path
    }

    private fun addCurve(path: Path, x: FloatArray, y: FloatArray) {
        // Curve run right to left (bottom of a band): same slopes as the forward way
        val back = x.size > 1 && x[1] < x[0]
        val slopes = if (back) curveSlopes(x.reversedArray(), y.reversedArray()).reversedArray() else curveSlopes(x, y)
        for (i in 0 until x.size - 1) {
            val h = (x[i + 1] - x[i]) / 3f
            path.cubicTo(x[i] + h, y[i] + slopes[i] * h, x[i + 1] - h, y[i + 1] - slopes[i + 1] * h, x[i + 1], y[i + 1])
        }
    }

    override fun onDraw(canvas: Canvas) {
        // Curve across the full width, the scale is written on top (#44)
        val axisH = if (startLabel != null || endLabel != null) label.textSize + 6 * d else 0f
        val left = 0f; val top = 6 * d; val bottom = height - 6 * d - axisH; val right = width.toFloat()
        if (values.size < 2) {
            canvas.drawText(context.getString(emptyText), width / 2f, height / 2f, empty)
            return
        }
        // Stacked: the scale starts from zero, otherwise the bottom of the layers would be cut
        val peak = values.maxOrNull()!!
        val hi = scaleTops?.let { tops -> tops.firstOrNull { peak <= it } ?: tops.last() } ?: (peak + 2f)
        val lo = if (layers != null) 0f else (values.minOrNull()!! - 2f).coerceIn(0f, hi - 2f)
        fun y(v: Float) = bottom - (v.coerceAtMost(hi) - lo) / (hi - lo) * (bottom - top)
        val scale = listOf(lo, (lo + hi) / 2, hi)
        scale.forEach { v -> canvas.drawLine(left, y(v), right, y(v), grid) }
        val xs = positions
        val path = curvePath(
            FloatArray(values.size) { i -> left + (xs?.get(i) ?: (i.toFloat() / (values.size - 1))) * (right - left) },
            FloatArray(values.size) { i -> y(values[i]) })
        val first = xs?.first()?.let { left + it * (right - left) } ?: left
        val last = xs?.last()?.let { left + it * (right - left) } ?: right
        val area = Path(path).apply { lineTo(last, bottom); lineTo(first, bottom); close() }
        val stack = layers
        if (stack != null) {
            drawLayers(canvas, stack, FloatArray(values.size) { i -> left + (xs?.get(i) ?: (i.toFloat() / (values.size - 1))) * (right - left) }, top, bottom, ::y)
        } else {
            line.color = app.mg4dash.ui.Themes.energy(context)
            val rgb = line.color and 0xFFFFFF
            fill.shader = LinearGradient(0f, top, 0f, bottom, 0x55000000 or rgb, rgb, Shader.TileMode.CLAMP)
            canvas.drawPath(area, fill)
            canvas.drawPath(path, line)
        }
        // Horizontal scale: start on the left, end on the right
        if (axisH > 0f) {
            val ty = height - 4 * d
            startLabel?.let { label.textAlign = Paint.Align.LEFT; canvas.drawText(it, left, ty, label) }
            endLabel?.let { label.textAlign = Paint.Align.RIGHT; canvas.drawText(it, right, ty, label) }
            label.textAlign = Paint.Align.LEFT
        }
        // Scale values above their line, on the curve
        scale.forEachIndexed { i, v ->
            val ty = if (i == scale.lastIndex) y(v) + label.textSize + 2 * d else y(v) - 4 * d
            canvas.drawText(String.format(Locale.getDefault(), "%.0f", v), 2 * d, ty, label)
        }
        // Average: drawn as a path (drawLine ignores dashes in hardware rendering, #44),
        // with a label of its value at the right end
        val avg = average
        val ay = y(avg)
        averageColor?.let { avgPaint.color = it }
        canvas.drawPath(Path().apply { moveTo(left, ay); lineTo(right, ay) }, avgPaint)
        // Last km: a dot on the curve's right end, inside the view
        lastDotColor?.let { col ->
            dot.color = col
            canvas.drawCircle(last - 5 * d, y(lastDotValue ?: values.last()), 5 * d, dot)
        }
        if (!averageBubble) return
        val txt = String.format(Locale.getDefault(), "%.1f", avg)
        val tw = avgText.measureText(txt)
        val padH = 7 * d; val h = avgText.textSize + 6 * d
        val bx1 = right - 2 * d; val bx0 = bx1 - tw - 2 * padH
        // Above the line, or below if it touches the top of the chart
        val above = ay - h - 4 * d >= top
        val by0 = if (above) ay - h - 4 * d else ay + 4 * d
        bubble.set(bx0, by0, bx1, by0 + h)
        canvas.drawRoundRect(bubble, h / 2, h / 2, avgFill)
        canvas.drawText(txt, (bx0 + bx1) / 2, by0 + h / 2 - (avgText.descent() + avgText.ascent()) / 2, avgText)
    }

    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubble = android.graphics.RectF()
    private val avgFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = app.mg4dash.ui.Themes.color(context, R.color.accent_action) }
    private val avgText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = app.mg4dash.ui.Themes.color(context, R.color.on_action); textSize = 22f * d; textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
}

/**
 * Instantaneous power, zero offset to the left: on the left regeneration (green, up to [maxRegenKw]),
 * on the right power drawn from the battery (button colour, up to [maxDrawKw]).
 */
/**
 * Two-way power bar: the 0 in the centre (offset according to the ranges), consumption goes
 * right as a gradient (cyan → blue → orange), regeneration left in green. The end at
 * 0 is straight, the outer end rounded with a dot. A marker keeps the peak of the last 10
 * seconds then falls gently; the value animates.
 */
class PowerBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    /** Tick size (sp); larger in the "power" energy card (#29). */
    var labelSp = 12f
    var kw: Float = 0f
        set(v) {
            if (v == field) return   // same value at each home refresh: nothing to redraw
            field = v
            if (v > maxDrawKw) { maxDrawKw = Math.ceil(v / 50.0).toFloat() * 50f; onScale?.invoke(maxDrawKw) }
            if (-v > maxRegenKw) { maxRegenKw = Math.ceil(-v / 25.0).toFloat() * 25f }
            val now = System.currentTimeMillis()
            if (v >= peak || now - peakAt > PEAK_HOLD_MS && v > 0f) { if (v >= peak) { peak = v; peakAt = now } }
            if (v <= -peakRegen || now - peakRegenAt > PEAK_HOLD_MS && v < 0f) { if (-v >= peakRegen) { peakRegen = -v; peakRegenAt = now } }
            postInvalidateOnAnimation()
        }
    var maxRegenKw = 50f
    var maxDrawKw = 140f
    /** Model's continuous and maximum power (MG4 manual): orange above continuous, red towards max (#16). */
    var continuousKw = 54f
    var peakKw = 125f
    /** Called when the scale widens (measured power above the expected maximum). */
    var onScale: ((Float) -> Unit)? = null

    companion object {
        private const val PEAK_HOLD_MS = 10_000L; private const val DECAY_KW_PER_S = 8f
        const val XPOWER_PEAK_KW = 320f
        /** Above 350 hp measured, only an XPower (435 hp) is possible: the car does not give its version. */
        const val XPOWER_DETECT_KW = 257f
        /** Model's maximum power (reference for the animation's 80 %). */
        fun peakFor(batteryModel: Int, xpower: Boolean) = when { xpower -> XPOWER_PEAK_KW; batteryModel == 51 -> 125f; else -> 150f }
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()
    private val d = resources.displayMetrics.density
    private val off = app.mg4dash.ui.Themes.color(context, R.color.gauge_off)
    private val regen = app.mg4dash.ui.Themes.color(context, R.color.accent_energy)
    private val cold = app.mg4dash.ui.Themes.color(context, R.color.accent_cold)
    private val draw = app.mg4dash.ui.Themes.color(context, R.color.accent_action)
    private val heat = app.mg4dash.ui.Themes.color(context, R.color.accent_heat)
    private val danger = app.mg4dash.ui.Themes.color(context, R.color.accent_danger)
    private val tick = app.mg4dash.ui.Themes.color(context, R.color.text_sub)

    private var shown = 0f          // displayed value (animated towards kw)
    private var first = true
    private var lastFrame = 0L
    private var peak = 0f; private var peakAt = 0L
    private var peakRegen = 0f; private var peakRegenAt = 0L

    /** Full bar on one side: straight end at 0, rounded outer end. */
    private fun fill(left: Float, top: Float, right: Float, bottom: Float, roundRight: Boolean) {
        val r = (bottom - top) / 2f
        val radii = if (roundRight) floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f) else floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
        path.reset(); rect.set(left, top, right, bottom); path.addRoundRect(rect, radii, Path.Direction.CW)
    }

    /**
     * Model's reference powers (MG4 manual, net battery power): 51 kWh 54 kW continuous /
     * 125 kW max, others 68 / 150 (#16), XPower 320 kW (435 hp); scale with a margin for losses.
     */
    fun configureFor(batteryModel: Int, xpower: Boolean) {
        continuousKw = if (!xpower && batteryModel == 51) 54f else 68f
        peakKw = peakFor(batteryModel, xpower)
        maxDrawKw = when { xpower -> 350f; batteryModel == 77 -> 200f; batteryModel == 64 -> 170f; else -> 140f }
    }

    /** X position of a power on the bar (view coordinates), same calculation as onDraw: for PowerFx. */
    fun xAt(v: Float): Float {
        val h = height.toFloat()
        val barH = (if (h >= 26 * d) h * 0.42f else h * 0.6f).coerceAtMost(14 * d)
        val x0 = barH; val x1 = width - barH
        val zero = x0 + (x1 - x0) * maxRegenKw / (maxRegenKw + maxDrawKw)
        return if (v >= 0f) zero + (x1 - zero) * (v / maxDrawKw).coerceIn(0f, 1f) else zero - (zero - x0) * (-v / maxRegenKw).coerceIn(0f, 1f)
    }

    /** Height of the bar's middle (view coordinates). */
    fun barCenterY(): Float {
        val h = height.toFloat()
        val labels = h >= 26 * d
        val barH = (if (labels) h * 0.42f else h * 0.6f).coerceAtMost(14 * d)
        return (if (labels) 4 * d else (h - barH) / 2f) + barH / 2f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val now = System.currentTimeMillis()
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1000f).coerceAtMost(0.1f)
        lastFrame = now
        // Gentle animation of the value, and slow fall of the peaks after 10 s
        if (first) { shown = kw; first = false }   // first display: directly the value
        shown += (kw - shown) * (1f - Math.exp(-dt * 10.0).toFloat())
        if (now - peakAt > PEAK_HOLD_MS) peak = maxOf(kw.coerceAtLeast(0f), peak - DECAY_KW_PER_S * dt)
        if (now - peakRegenAt > PEAK_HOLD_MS) peakRegen = maxOf((-kw).coerceAtLeast(0f), peakRegen - DECAY_KW_PER_S * dt)

        val labels = h >= 26 * d
        val barH = (if (labels) h * 0.42f else h * 0.6f).coerceAtMost(14 * d)
        val top = if (labels) 4 * d else (h - barH) / 2f
        val bottom = top + barH
        val pad = barH                       // room for the dot at the ends
        val x0 = pad; val x1 = w - pad
        val zero = x0 + (x1 - x0) * maxRegenKw / (maxRegenKw + maxDrawKw)
        fun xFor(v: Float) = if (v >= 0f) zero + (x1 - zero) * (v / maxDrawKw).coerceIn(0f, 1f)
            else zero - (zero - x0) * (-v / maxRegenKw).coerceIn(0f, 1f)

        paint.shader = null
        paint.color = off
        rect.set(x0, top, x1, bottom); canvas.drawRoundRect(rect, barH / 2f, barH / 2f, paint)

        val end = xFor(shown)
        if (shown > 0.05f) {
            // Blue up to continuous power, orange beyond, red from 80 % of the maximum
            val endCol = when { shown >= peakKw * 0.8f -> danger; shown > continuousKw -> heat; else -> draw }
            paint.shader = LinearGradient(zero, 0f, maxOf(end, zero + barH), 0f, intArrayOf(cold, endCol), null, Shader.TileMode.CLAMP)
            fill(zero, top, maxOf(end, zero + barH), bottom, roundRight = true); canvas.drawPath(path, paint)
        } else if (shown < -0.05f) {
            paint.shader = LinearGradient(zero, 0f, x0, 0f, intArrayOf(0x66000000 or (regen and 0xFFFFFF), regen), null, Shader.TileMode.CLAMP)
            fill(minOf(end, zero - barH), top, zero, bottom, roundRight = false); canvas.drawPath(path, paint)
        }
        paint.shader = null

        // Dot at the end, with a halo
        if (kotlin.math.abs(shown) > 0.05f) {
            val col = if (shown > 0f) (when { shown >= peakKw * 0.8f -> danger; shown > continuousKw -> heat; else -> draw }) else regen
            val cy = (top + bottom) / 2f
            paint.color = col; paint.alpha = 70; canvas.drawCircle(end, cy, barH * 0.95f, paint)
            paint.alpha = 255; paint.color = 0xFFFFFFFF.toInt(); canvas.drawCircle(end, cy, barH * 0.42f, paint)
        }

        // Peaks of the last 10 seconds: small line above the bar
        paint.color = tick
        if (peak > 1f && peak > shown + 1f) { val px = xFor(peak); canvas.drawRect(px - d, top - 3 * d, px + d, bottom + 3 * d, paint) }
        if (peakRegen > 1f && -peakRegen < shown - 1f) {
            val px = xFor(-peakRegen); paint.color = regen; canvas.drawRect(px - d, top - 3 * d, px + d, bottom + 3 * d, paint)
        }

        // Discreet markers: continuous power and 80 % of the maximum
        paint.color = tick; paint.alpha = 110
        listOf(continuousKw, peakKw * 0.8f).filter { it < maxDrawKw }.forEach { k ->
            val px = xFor(k); canvas.drawRect(px - d * 0.6f, top, px + d * 0.6f, bottom, paint)
        }
        paint.alpha = 255

        // 0 marker and ticks
        paint.color = tick
        canvas.drawRect(zero - d, top - 4 * d, zero + d, bottom + 4 * d, paint)
        if (labels) {
            text.color = tick; text.textSize = labelSp * resources.displayMetrics.scaledDensity
            val ty = h - 1 * d
            text.textAlign = Paint.Align.LEFT; canvas.drawText("−${maxRegenKw.toInt()}", x0, ty, text)
            text.textAlign = Paint.Align.CENTER; canvas.drawText("0", zero, ty, text)
            text.textAlign = Paint.Align.RIGHT; canvas.drawText("+${maxDrawKw.toInt()}", x1, ty, text)
        }

        // While the value moves or a peak falls, redraw
        // Redraw only if it moves by at least half a pixel: when stopped, small power
        // variations (0.1 to 0.3 kW) restarted the animation endlessly, at 30 frames per second
        // (peaks held for 10 s: no animation during the hold, a single redraw when the fall starts)
        // Each peak falls towards its side's value (0 when power is on the other side)
        val peakUp = kotlin.math.abs(xFor(peak) - xFor(kw.coerceAtLeast(0f))) > 0.5f
        val regenUp = kotlin.math.abs(xFor(-peakRegen) - xFor(kw.coerceAtMost(0f))) > 0.5f
        val decaying = (peakUp && now - peakAt > PEAK_HOLD_MS) || (regenUp && now - peakRegenAt > PEAK_HOLD_MS)
        if (kotlin.math.abs(xFor(kw) - xFor(shown)) > 0.5f || decaying) postInvalidateOnAnimation()
        else {
            shown = kw; lastFrame = 0L
            val holdLeft = listOfNotNull((PEAK_HOLD_MS - (now - peakAt)).takeIf { peakUp }, (PEAK_HOLD_MS - (now - peakRegenAt)).takeIf { regenUp }).minOrNull()
            if (holdLeft != null) postInvalidateDelayed(holdLeft.coerceAtLeast(16L))
        }
    }
}
