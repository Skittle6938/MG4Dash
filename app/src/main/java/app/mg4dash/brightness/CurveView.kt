package app.mg4dash.brightness

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import app.mg4dash.R
import app.mg4dash.ui.Themes

/**
 * The smart brightness's curve in Settings › Look (20/11, mockup v2): the default dashed, the learnt one, its
 * points, the light now. Edited by hand: a touch on a point selects it and a vertical slide moves it (kept between its
 * neighbours, the curve still rising); a touch elsewhere adds a point there; [deleteSelected] takes the selected one
 * off. Each change is saved once the finger is lifted ([onChange]).
 */
@SuppressLint("ViewConstructor")
class CurveView(context: Context, private var curve: BrightnessCurve, private val now: () -> Double?,
                private val onChange: (BrightnessCurve) -> Unit, private val onSelect: (Boolean) -> Unit) : View(context) {
    private val d = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14 * resources.displayMetrics.scaledDensity }
    private val value = Themes.color(context, R.color.text_value)
    private val sub = Themes.color(context, R.color.text_sub)
    private val title = Themes.color(context, R.color.text_title)
    private val grid = Themes.color(context, R.color.panel_border)

    /** The selected point, null when none. */
    var selected: BrightnessCurve.Point? = null
        private set(v) { field = v; onSelect(v != null); invalidate() }

    fun set(c: BrightnessCurve) { curve = c; selected = selected?.let { s -> c.points.firstOrNull { it.level == s.level } }; invalidate() }

    fun deleteSelected() {
        val p = selected ?: return
        curve = curve.without(p); selected = null; onChange(curve)
    }

    // The plot: light (log10 lux 0 to 5) across, brightness (0 to 1) up, margins for the labels
    private val left get() = 44 * d
    private val right get() = width - 10 * d
    private val top get() = 10 * d
    private val bottom get() = height - 30 * d
    private fun x(level: Double) = (left + (right - left) * level / BrightnessCurve.MAX_LEVEL).toFloat()
    private fun y(v: Double) = (bottom - (bottom - top) * v).toFloat()
    private fun levelAt(px: Float) = ((px - left) / (right - left) * BrightnessCurve.MAX_LEVEL).toDouble().coerceIn(0.0, BrightnessCurve.MAX_LEVEL)
    private fun valueAt(py: Float) = ((bottom - py) / (bottom - top)).toDouble().coerceIn(0.0, 1.0)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 0.42f).toInt().coerceAtMost((300 * d).toInt()))
    }

    override fun onDraw(c: Canvas) {
        // Grid: 0, 50, 100 %, and the four lights of the default
        line.pathEffect = null; line.strokeWidth = 1 * d; line.color = grid
        for (v in listOf(0.0, 0.5, 1.0)) c.drawLine(left, y(v), right, y(v), line)
        text.color = title; text.textAlign = Paint.Align.RIGHT
        for ((v, s) in listOf(0.0 to "0 %", 0.5 to "50 %", 1.0 to "100 %")) c.drawText(s, left - 6 * d, y(v) + 5 * d, text)
        text.textAlign = Paint.Align.CENTER
        val names = listOf(R.string.br_night, R.string.br_dusk, R.string.br_overcast, R.string.br_sun)
        BrightnessCurve.DEFAULT.forEachIndexed { i, (lux, _) -> c.drawText(context.getString(names[i]), x(Math.log10(lux)), height - 8 * d, text) }
        // The default, dashed
        line.color = title; line.strokeWidth = 2 * d; line.pathEffect = DashPathEffect(floatArrayOf(7 * d, 6 * d), 0f)
        c.drawPath(path { BrightnessCurve.defaultAt(it) }, line)
        // The learnt one
        line.pathEffect = null; line.color = value; line.strokeWidth = 3 * d
        c.drawPath(path { curve.valueAt(it) }, line)
        // The light now
        now()?.let { lv ->
            line.color = sub; line.strokeWidth = 1.5f * d; line.pathEffect = DashPathEffect(floatArrayOf(3 * d, 5 * d), 0f)
            c.drawLine(x(lv), top, x(lv), bottom, line); line.pathEffect = null
            fill.color = sub; c.drawCircle(x(lv), y(curve.valueAt(lv)), 5 * d, fill)
        }
        // The points, the selected one ringed with its value
        for (p in curve.points) {
            fill.color = value; c.drawCircle(x(p.level), y(p.value), 6 * d, fill)
            if (p == selected) {
                line.color = value; line.strokeWidth = 2 * d; c.drawCircle(x(p.level), y(p.value), 12 * d, line)
                text.color = value; text.textAlign = Paint.Align.LEFT
                c.drawText("${Math.round(p.value * 100)} %", x(p.level) + 16 * d, y(p.value) - 6 * d, text)
            }
        }
    }

    private fun path(f: (Double) -> Double) = Path().apply {
        var lv = 0.0
        moveTo(x(lv), y(f(lv)))
        while (lv < BrightnessCurve.MAX_LEVEL) { lv += 0.05; lineTo(x(lv), y(f(lv))) }
    }

    private var dragging: BrightnessCurve.Point? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val near = curve.points.minByOrNull { Math.hypot((x(it.level) - e.x).toDouble(), (y(it.value) - e.y).toDouble()) }
                    ?.takeIf { Math.hypot((x(it.level) - e.x).toDouble(), (y(it.value) - e.y).toDouble()) < 28 * d }
                if (near != null) { selected = near; dragging = near }
                else {
                    // A new point on the curve at this light, then moved with the finger
                    val lv = levelAt(e.x)
                    val (lo, hi) = curve.bounds(lv)
                    val p = BrightnessCurve.Point(lv, false, curve.valueAt(lv).coerceIn(lo, hi), System.currentTimeMillis())
                    curve = BrightnessCurve((curve.points.filter { Math.abs(it.level - lv) >= BrightnessCurve.REPLACE } + p).takeLast(BrightnessCurve.MAX_POINTS))
                    selected = p; dragging = p
                }
            }
            MotionEvent.ACTION_MOVE -> dragging?.let { p ->
                curve = curve.moved(p, valueAt(e.y), p.at)
                val np = curve.points.first { it.level == p.level }
                dragging = np; selected = np
            }
            MotionEvent.ACTION_UP -> { if (dragging != null) onChange(curve); dragging = null }
            MotionEvent.ACTION_CANCEL -> dragging = null
        }
        return true
    }
}
