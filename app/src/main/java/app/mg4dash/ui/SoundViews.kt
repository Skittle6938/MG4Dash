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

    /** Ton de la voiture : gris clair transparent (couleur du texte, atténuée). */
    private fun tone(alpha: Int) = (c(R.color.text_value) and 0x00FFFFFF) or (alpha shl 24)

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        // MG4 vue de dessus, avant en haut : 4,29 × 1,84 m, roues aux quatre coins, porte-à-faux courts
        val ch = h - 56 * d; val cw = ch * 0.43f
        car.set((w - cw) / 2, 28 * d, (w + cw) / 2, 28 * d + ch)
        val l = car.left; val t = car.top; val cx = car.centerX()
        fun X(f: Float) = l + cw * f
        fun Y(f: Float) = t + ch * f
        // Rétroviseurs
        fill.color = tone(90)
        cv.drawRoundRect(RectF(X(-0.13f), Y(0.3f), X(0.03f), Y(0.345f)), 8 * d, 8 * d, fill)
        cv.drawRoundRect(RectF(X(0.97f), Y(0.3f), X(1.13f), Y(0.345f)), 8 * d, 8 * d, fill)
        // Caisse : nez arrondi et plongeant, flancs galbés, arrière court et droit
        val shell = Path().apply {
            moveTo(cx, Y(0f))
            cubicTo(X(0.82f), Y(0f), X(0.97f), Y(0.02f), X(0.985f), Y(0.1f))
            cubicTo(X(1.02f), Y(0.3f), X(1.0f), Y(0.7f), X(0.98f), Y(0.93f))
            cubicTo(X(0.97f), Y(0.99f), X(0.8f), Y(1f), cx, Y(1f))
            cubicTo(X(0.2f), Y(1f), X(0.03f), Y(0.99f), X(0.02f), Y(0.93f))
            cubicTo(X(0f), Y(0.7f), X(-0.02f), Y(0.3f), X(0.015f), Y(0.1f))
            cubicTo(X(0.03f), Y(0.02f), X(0.18f), Y(0f), cx, Y(0f))
            close()
        }
        // Pneus : seule la partie qui dépasse de la caisse est dessinée
        cv.save(); cv.clipOutPath(shell)
        fill.color = tone(90)
        for (fy in listOf(0.12f, 0.77f)) for (fx in listOf(-0.05f, 0.87f))
            cv.drawRoundRect(RectF(X(fx), Y(fy), X(fx + 0.18f), Y(fy + 0.12f)), 6 * d, 6 * d, fill)
        cv.restore()
        fill.color = tone(40); cv.drawPath(shell, fill)
        body.color = tone(120); cv.drawPath(shell, body)
        // Capot (nervures) et bandeau lumineux avant, comme la MG4
        line.color = tone(70)
        cv.drawLine(X(0.3f), Y(0.05f), X(0.33f), Y(0.22f), line); cv.drawLine(X(0.7f), Y(0.05f), X(0.67f), Y(0.22f), line)
        fill.color = tone(160)
        cv.drawRoundRect(RectF(X(0.1f), Y(0.012f), X(0.32f), Y(0.026f)), 4 * d, 4 * d, fill)
        cv.drawRoundRect(RectF(X(0.68f), Y(0.012f), X(0.9f), Y(0.026f)), 4 * d, 4 * d, fill)
        // Pare-brise, toit vitré et lunette (un seul vitrage sombre, montants fins)
        val glass = Path().apply {
            moveTo(X(0.16f), Y(0.27f)); cubicTo(X(0.35f), Y(0.235f), X(0.65f), Y(0.235f), X(0.84f), Y(0.27f))
            lineTo(X(0.86f), Y(0.36f)); lineTo(X(0.87f), Y(0.78f))
            cubicTo(X(0.7f), Y(0.82f), X(0.3f), Y(0.82f), X(0.13f), Y(0.78f))
            lineTo(X(0.14f), Y(0.36f)); close()
        }
        fill.color = tone(55); cv.drawPath(glass, fill)
        line.color = tone(110)
        cv.drawLine(X(0.14f), Y(0.36f), X(0.86f), Y(0.36f), line)   // haut du pare-brise
        cv.drawLine(X(0.135f), Y(0.7f), X(0.865f), Y(0.7f), line)   // haut de la lunette
        // Sièges vus à travers le toit
        fill.color = tone(45)
        for ((sx, sy) in listOf(0.3f to 0.42f, 0.7f to 0.42f, 0.3f to 0.58f, 0.7f to 0.58f))
            cv.drawRoundRect(RectF(X(sx - 0.14f), Y(sy - 0.045f), X(sx + 0.14f), Y(sy + 0.045f)), 8 * d, 8 * d, fill)
        // Bandeau de feux arrière sur toute la largeur
        fill.color = tone(160)
        cv.drawRoundRect(RectF(X(0.08f), Y(0.975f), X(0.92f), Y(0.988f)), 4 * d, 4 * d, fill)
        // Haut-parleurs (portes avant et arrière)
        fill.color = c(R.color.text_title)
        for ((sx, sy) in listOf(0.01f to 0.4f, 0.99f to 0.4f, 0.01f to 0.62f, 0.99f to 0.62f))
            cv.drawCircle(X(sx), Y(sy), 5.5f * d, fill)
        // Repères et libellés
        text.color = c(R.color.text_title)
        cv.drawText(context.getString(R.string.snd_front), w / 2, 18 * d, text)
        cv.drawText(context.getString(R.string.snd_rear), w / 2, h - 8 * d, text)
        cv.drawText(context.getString(R.string.snd_left), car.left - 56 * d, h / 2 + 5 * d, text)
        cv.drawText(context.getString(R.string.snd_right), car.right + 56 * d, h / 2 + 5 * d, text)
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

    /** Spectre de la musique en cours (0…1 par barre, des graves aux aigus), dessiné derrière ; null = rien. */
    var spectrum: FloatArray? = null; set(v) { field = v; invalidate() }

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
        // Spectre en direct, en transparence derrière les curseurs
        spectrum?.let { sp ->
            val n = sp.size; val bw = width.toFloat() / n
            fill.color = Themes.select(context)
            for (k in 0 until n) {
                val v = sp[k].coerceIn(0f, 1f); if (v <= 0.01f) continue
                fill.alpha = 30 + (v * 45).toInt()
                cv.drawRoundRect(RectF(k * bw + bw * 0.18f, bottom - v * (bottom - top), (k + 1) * bw - bw * 0.18f, bottom), 3 * d, 3 * d, fill)
            }
            fill.alpha = 255
        }
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
