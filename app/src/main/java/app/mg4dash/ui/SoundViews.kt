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

    /** Photo de la MG4 vue de dessus (celle de WinClose), passée en gris clair transparent. */
    private val photo: android.graphics.Bitmap? = try {
        android.graphics.BitmapFactory.decodeResource(resources, R.drawable.mg4_top)
    } catch (_: Throwable) { null }
    private val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var photoTone = 0
    /** Caisse (sans les rétroviseurs) : zone où l'on place le point de son. */
    private val shell = RectF()

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        // Photo à l'échelle de la hauteur disponible, avant en haut
        // (la photo a une marge transparente : on la laisse déborder un peu sur les libellés avant / arrière)
        val ch = h - 16 * d
        val ratio = photo?.let { it.width.toFloat() / it.height } ?: 0.63f
        val cw = ch * ratio
        car.set((w - cw) / 2, 8 * d, (w + cw) / 2, 8 * d + ch)
        shell.set(car.left + cw * 0.2f, car.top + ch * 0.07f, car.left + cw * 0.81f, car.top + ch * 0.93f)
        photo?.let { bmp ->
            // Gris clair (sombre en thème clair) : luminance de la photo ramenée vers la couleur du texte
            val tone = c(R.color.text_value)
            if (photoPaint.colorFilter == null || photoTone != tone) {
                photoTone = tone
                val r = android.graphics.Color.red(tone) / 255f; val g = android.graphics.Color.green(tone) / 255f; val b = android.graphics.Color.blue(tone) / 255f
                val dark = (r + g + b) < 1.5f
                // Gris : luminance, éclaircie (thème sombre) ou assombrie (thème clair), puis transparence
                val k = if (dark) 0.75f else 0.9f; val off = if (dark) 60f else 90f
                val m = android.graphics.ColorMatrix(floatArrayOf(
                    0.299f * k, 0.587f * k, 0.114f * k, 0f, off,
                    0.299f * k, 0.587f * k, 0.114f * k, 0f, off,
                    0.299f * k, 0.587f * k, 0.114f * k, 0f, off,
                    0f, 0f, 0f, 0.6f, 0f))
                photoPaint.colorFilter = android.graphics.ColorMatrixColorFilter(m)
            }
            cv.drawBitmap(bmp, null, car, photoPaint)
        }
        // Haut-parleurs (portes avant et arrière)
        fill.color = c(R.color.text_title)
        for ((sx, sy) in listOf(0f to 0.4f, 1f to 0.4f, 0f to 0.64f, 1f to 0.64f))
            cv.drawCircle(shell.left + shell.width() * sx, car.top + ch * sy, 5.5f * d, fill)
        // Repères et libellés
        text.color = c(R.color.text_title)
        cv.drawText(context.getString(R.string.snd_front), w / 2, 18 * d, text)
        cv.drawText(context.getString(R.string.snd_rear), w / 2, h - 8 * d, text)
        cv.drawText(context.getString(R.string.snd_left), car.left - 40 * d, h / 2 + 5 * d, text)
        cv.drawText(context.getString(R.string.snd_right), car.right + 40 * d, h / 2 + 5 * d, text)
        // Point de son
        val px = shell.left + shell.width() * (0.5f + balance / (2f * RANGE) * 0.9f)
        val py = shell.top + shell.height() * (0.5f - fader / (2f * RANGE) * 0.85f)
        line.color = Themes.select(context); line.alpha = 120
        cv.drawLine(shell.left, py, shell.right, py, line); cv.drawLine(px, shell.top, px, shell.bottom, line)
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
            val b = (((e.x - shell.left) / shell.width() - 0.5f) / 0.9f * 2f * RANGE).roundToInt()
            val f = ((0.5f - (e.y - shell.top) / shell.height()) / 0.85f * 2f * RANGE).roundToInt()
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
