package app.mg4dash.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import app.mg4dash.R
import kotlin.math.roundToInt

/**
 * Répartition du son vue de dessus : la voiture, ses haut-parleurs et un point à faire glisser
 * (balance gauche / droite en x, fader avant / arrière en y). Double toucher = recentrer.
 */
class SoundFieldView(context: Context) : View(context) {
    /** −RANGE (gauche / arrière) … +RANGE (droite / avant). */
    var balance = 0; set(v) { field = v.coerceIn(-RANGE, RANGE); invalidate() }
    var fader = 0; set(v) { field = v.coerceIn(-RANGE, RANGE); invalidate() }
    var onChange: ((balance: Int, fader: Int) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private fun c(res: Int) = Themes.color(context, res)
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.5f * d }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 15 * resources.displayMetrics.scaledDensity }
    private val car = RectF()
    private var lastTap = 0L

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        // Voiture : proportions d'une MG4 vue de dessus, avant en haut
        val ch = h - 56 * d; val cw = ch * 0.46f
        car.set((w - cw) / 2, 28 * d, (w + cw) / 2, 28 * d + ch)
        val r = cw * 0.32f
        fill.color = c(R.color.panel_raised); cv.drawRoundRect(car, r, r, fill)
        body.color = c(R.color.panel_border); cv.drawRoundRect(car, r, r, body)
        // Pare-brise et lunette
        val glass = Paint(fill).apply { color = c(R.color.button_idle) }
        cv.drawRoundRect(RectF(car.left + cw * 0.12f, car.top + ch * 0.22f, car.right - cw * 0.12f, car.top + ch * 0.36f), 10 * d, 10 * d, glass)
        cv.drawRoundRect(RectF(car.left + cw * 0.16f, car.bottom - ch * 0.18f, car.right - cw * 0.16f, car.bottom - ch * 0.09f), 8 * d, 8 * d, glass)
        // Sièges
        val seat = Paint(fill).apply { color = c(R.color.button_idle) }
        val sw = cw * 0.3f; val sh = ch * 0.12f
        for ((cx, cy) in listOf(0.3f to 0.45f, 0.7f to 0.45f, 0.3f to 0.63f, 0.7f to 0.63f)) {
            val x = car.left + cw * cx; val y = car.top + ch * cy
            cv.drawRoundRect(RectF(x - sw / 2, y - sh / 2, x + sw / 2, y + sh / 2), 8 * d, 8 * d, seat)
        }
        // Haut-parleurs (portes avant et arrière)
        val spk = Paint(fill).apply { color = c(R.color.text_title) }
        for ((sx, sy) in listOf(0f to 0.4f, 1f to 0.4f, 0f to 0.66f, 1f to 0.66f))
            cv.drawCircle(car.left + cw * sx, car.top + ch * sy, 6 * d, spk)
        // Repères et libellés
        text.color = c(R.color.text_title)
        cv.drawText(context.getString(R.string.snd_front), w / 2, 18 * d, text)
        cv.drawText(context.getString(R.string.snd_rear), w / 2, h - 8 * d, text)
        cv.drawText(context.getString(R.string.snd_left), car.left - 34 * d, h / 2 + 5 * d, text)
        cv.drawText(context.getString(R.string.snd_right), car.right + 34 * d, h / 2 + 5 * d, text)
        // Point de son
        val px = car.left + cw * (0.5f + balance / (2f * RANGE) * 0.9f)
        val py = car.top + ch * (0.54f - fader / (2f * RANGE) * 0.8f)
        line.color = Themes.select(context); line.alpha = 120
        cv.drawLine(car.left, py, car.right, py, line); cv.drawLine(px, car.top, px, car.bottom, line)
        fill.color = Themes.select(context); fill.alpha = 60; cv.drawCircle(px, py, 26 * d, fill)
        fill.alpha = 255; cv.drawCircle(px, py, 13 * d, fill)
        fill.color = c(R.color.panel); cv.drawCircle(px, py, 5 * d, fill)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (car.width() <= 0f) return false
        if (e.action == MotionEvent.ACTION_DOWN) {
            val now = e.eventTime
            if (now - lastTap < 300) { balance = 0; fader = 0; onChange?.invoke(0, 0); lastTap = 0; return true }
            lastTap = now
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) {
            val b = (((e.x - car.left) / car.width() - 0.5f) / 0.9f * 2f * RANGE).roundToInt()
            val f = ((0.54f - (e.y - car.top) / car.height()) / 0.8f * 2f * RANGE).roundToInt()
            if (b.coerceIn(-RANGE, RANGE) != balance || f.coerceIn(-RANGE, RANGE) != fader) {
                balance = b; fader = f; onChange?.invoke(balance, fader)
            }
        }
        return true
    }

    companion object { const val RANGE = 7 }
}

/**
 * Égaliseur : un curseur vertical par bande, la courbe passe par les curseurs.
 * Valeurs −RANGE … +RANGE ; glisser un curseur le règle.
 */
class EqualizerView(context: Context) : View(context) {
    val labels = listOf("80 Hz", "500 Hz", "1 kHz", "5 kHz", "16 kHz")
    val values = IntArray(labels.size)
    var onChange: ((band: Int, value: Int) -> Unit)? = null

    fun set(vs: IntArray) { vs.copyInto(values); invalidate() }

    private val d = resources.displayMetrics.density
    private val sd = resources.displayMetrics.scaledDensity
    private fun c(res: Int) = Themes.color(context, res)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeWidth = 8 * d }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val curve = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d; strokeJoin = Paint.Join.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1 * d }
    private var top = 0f; private var bottom = 0f
    private var drag = -1

    private fun x(i: Int) = width * (i + 0.5f) / values.size
    private fun y(v: Int) = bottom - (v + RANGE) / (2f * RANGE) * (bottom - top)

    override fun onDraw(cv: Canvas) {
        top = 44 * d; bottom = height - 40 * d
        // Repères 0 et ±RANGE
        grid.color = c(R.color.panel_border)
        for (v in listOf(-RANGE, 0, RANGE)) cv.drawLine(0f, y(v), width.toFloat(), y(v), grid)
        // Courbe lissée et zone sous la courbe
        val p = Path().apply {
            moveTo(0f, y(values[0]))
            lineTo(x(0), y(values[0]))
            for (i in 1 until values.size) {
                val mx = (x(i - 1) + x(i)) / 2
                cubicTo(mx, y(values[i - 1]), mx, y(values[i]), x(i), y(values[i]))
            }
            lineTo(width.toFloat(), y(values.last()))
        }
        val area = Path(p).apply { lineTo(width.toFloat(), bottom); lineTo(0f, bottom); close() }
        val acc = Themes.select(context)
        fill.shader = LinearGradient(0f, top, 0f, bottom, (acc and 0x00FFFFFF) or 0x55000000, acc and 0x00FFFFFF, Shader.TileMode.CLAMP)
        cv.drawPath(area, fill); fill.shader = null
        curve.color = acc; cv.drawPath(p, curve)
        for (i in values.indices) {
            val cx = x(i)
            track.color = c(R.color.gauge_off); cv.drawLine(cx, top, cx, bottom, track)
            track.color = acc; cv.drawLine(cx, y(0), cx, y(values[i]), track)
            fill.color = c(R.color.text_value); cv.drawCircle(cx, y(values[i]), 15 * d, fill)
            fill.color = acc; cv.drawCircle(cx, y(values[i]), 6 * d, fill)
            text.textSize = 20 * sd; text.color = c(R.color.text_value)
            cv.drawText((if (values[i] > 0) "+" else "") + values[i], cx, 26 * d, text)
            text.textSize = 15 * sd; text.color = c(R.color.text_title)
            cv.drawText(labels[i], cx, height - 10 * d, text)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { drag = (e.x / width * values.size).toInt().coerceIn(0, values.size - 1); parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { drag = -1; return true }
        }
        if (drag >= 0 && bottom > top) {
            val v = ((bottom - e.y) / (bottom - top) * 2f * RANGE - RANGE).roundToInt().coerceIn(-RANGE, RANGE)
            if (v != values[drag]) { values[drag] = v; invalidate(); onChange?.invoke(drag, v) }
        }
        return true
    }

    companion object { const val RANGE = 7 }
}
