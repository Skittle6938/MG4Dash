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

/** Barre horizontale empilée : répartition de l'énergie (conduite, clim, accessoires). */
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
     * Style de la barre de puissance (issue #29) : rail fin arrondi centré dans la vue, une part en
     * dégradé par poste, séparées d'une fente fine.
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

/** Courbe de consommation (kWh/100 km) avec aire dégradée et ligne de moyenne. */
/** Courbe lissée (#44) : chaque point = moyenne de lui-même et des 2 précédents. */
fun List<Float>.smooth3(): List<Float> = indices.map { i -> subList(maxOf(0, i - 2), i + 1).average().toFloat() }

class LineChart @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var values: List<Float> = emptyList()
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f * d; strokeJoin = Paint.Join.ROUND
        color = app.mg4dash.ui.Themes.color(context, R.color.accent_energy)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint().apply { color = app.mg4dash.ui.Themes.color(context, R.color.panel_border); strokeWidth = 1f }
    private val avgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * d
        color = app.mg4dash.ui.Themes.color(context, R.color.accent_action)   // couleur des boutons du thème
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(6 * d, 6 * d), 0f)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = app.mg4dash.ui.Themes.color(context, R.color.text_title); textSize = 14f * d
    }
    private val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = app.mg4dash.ui.Themes.color(context, R.color.text_sub); textSize = 17f * d; textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        val left = 34 * d; val top = 6 * d; val bottom = height - 6 * d; val right = width.toFloat()
        if (values.size < 2) {
            canvas.drawText(context.getString(app.mg4dash.R.string.chart_empty), width / 2f, height / 2f, empty)
            return
        }
        val lo = (values.minOrNull()!! - 2f).coerceAtLeast(0f)
        val hi = values.maxOrNull()!! + 2f
        fun y(v: Float) = bottom - (v - lo) / (hi - lo) * (bottom - top)
        listOf(lo, (lo + hi) / 2, hi).forEach { v ->
            canvas.drawLine(left, y(v), right, y(v), grid)
            canvas.drawText(String.format(Locale.getDefault(), "%.0f", v), 0f, y(v) + 4 * d, label)
        }
        val step = (right - left) / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v -> val x = left + i * step; if (i == 0) path.moveTo(x, y(v)) else path.lineTo(x, y(v)) }
        val area = Path(path).apply { lineTo(right, bottom); lineTo(left, bottom); close() }
        fill.shader = LinearGradient(0f, top, 0f, bottom, 0x5500E676, 0x0000E676, Shader.TileMode.CLAMP)
        canvas.drawPath(area, fill)
        canvas.drawPath(path, line)
        // Moyenne : tracée comme un chemin (drawLine ignore les pointillés en rendu matériel, #44),
        // avec une étiquette de sa valeur au bout à droite
        val avg = values.average().toFloat()
        val ay = y(avg)
        canvas.drawPath(Path().apply { moveTo(left, ay); lineTo(right, ay) }, avgPaint)
        val txt = String.format(Locale.getDefault(), "%.1f", avg)
        val tw = avgText.measureText(txt)
        val padH = 7 * d; val h = avgText.textSize + 6 * d
        val bx1 = right - 2 * d; val bx0 = bx1 - tw - 2 * padH
        // Au-dessus de la ligne, ou dessous si elle touche le haut du graphique
        val above = ay - h - 4 * d >= top
        val by0 = if (above) ay - h - 4 * d else ay + 4 * d
        bubble.set(bx0, by0, bx1, by0 + h)
        canvas.drawRoundRect(bubble, h / 2, h / 2, avgFill)
        canvas.drawText(txt, (bx0 + bx1) / 2, by0 + h / 2 - (avgText.descent() + avgText.ascent()) / 2, avgText)
    }

    private val bubble = android.graphics.RectF()
    private val avgFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = app.mg4dash.ui.Themes.color(context, R.color.accent_action) }
    private val avgText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = app.mg4dash.ui.Themes.color(context, R.color.on_action); textSize = 15f * d; textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
}

/**
 * Puissance instantanée, zéro décalé à gauche : à gauche la récupération (vert, jusqu'à [maxRegenKw]),
 * à droite la puissance tirée de la batterie (couleur des boutons, jusqu'à [maxDrawKw]).
 */
/**
 * Barre de puissance bidirectionnelle : le 0 au centre (décalé selon les plages), la consommation part
 * vers la droite en dégradé (cyan → bleu → orange), la récupération vers la gauche en vert. Le bout côté
 * 0 est droit, le bout extérieur arrondi avec une pastille. Un repère garde le pic des 10 dernières
 * secondes puis redescend doucement ; la valeur s'anime.
 */
class PowerBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    /** Taille des graduations (sp) ; plus grande dans la carte énergie « puissance » (#29). */
    var labelSp = 12f
    var kw: Float = 0f
        set(v) {
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
    /** Puissance continue et maximale du modèle (manuel MG4) : orange au-delà de la continue, rouge vers le max (#16). */
    var continuousKw = 54f
    var peakKw = 125f
    /** Appelé quand l'échelle s'élargit (puissance mesurée au-delà du maximum prévu). */
    var onScale: ((Float) -> Unit)? = null

    companion object {
        private const val PEAK_HOLD_MS = 10_000L; private const val DECAY_KW_PER_S = 8f
        const val XPOWER_PEAK_KW = 320f
        /** Au-delà de 350 ch mesurés, seule une XPower (435 ch) est possible : la voiture ne donne pas sa version. */
        const val XPOWER_DETECT_KW = 257f
        /** Puissance maximale du modèle (référence des 80 % de l'animation). */
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

    private var shown = 0f          // valeur affichée (animée vers kw)
    private var first = true
    private var lastFrame = 0L
    private var peak = 0f; private var peakAt = 0L
    private var peakRegen = 0f; private var peakRegenAt = 0L

    /** Barre pleine d'un côté : bout droit au 0, bout extérieur arrondi. */
    private fun fill(left: Float, top: Float, right: Float, bottom: Float, roundRight: Boolean) {
        val r = (bottom - top) / 2f
        val radii = if (roundRight) floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f) else floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
        path.reset(); rect.set(left, top, right, bottom); path.addRoundRect(rect, radii, Path.Direction.CW)
    }

    /**
     * Puissances de référence du modèle (manuel MG4, puissance nette batterie) : 51 kWh 54 kW en continu /
     * 125 kW max, autres 68 / 150 (#16), XPower 320 kW (435 ch) ; échelle avec une marge pour les pertes.
     */
    fun configureFor(batteryModel: Int, xpower: Boolean) {
        continuousKw = if (!xpower && batteryModel == 51) 54f else 68f
        peakKw = peakFor(batteryModel, xpower)
        maxDrawKw = when { xpower -> 350f; batteryModel == 77 -> 200f; batteryModel == 64 -> 170f; else -> 140f }
    }

    /** Abscisse d'une puissance sur la barre (coordonnées de la vue), même calcul que onDraw : pour PowerFx. */
    fun xAt(v: Float): Float {
        val h = height.toFloat()
        val barH = (if (h >= 26 * d) h * 0.42f else h * 0.6f).coerceAtMost(14 * d)
        val x0 = barH; val x1 = width - barH
        val zero = x0 + (x1 - x0) * maxRegenKw / (maxRegenKw + maxDrawKw)
        return if (v >= 0f) zero + (x1 - zero) * (v / maxDrawKw).coerceIn(0f, 1f) else zero - (zero - x0) * (-v / maxRegenKw).coerceIn(0f, 1f)
    }

    /** Hauteur du milieu de la barre (coordonnées de la vue). */
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
        // Animation douce de la valeur, et descente lente des pics après 10 s
        if (first) { shown = kw; first = false }   // premier affichage : directement la valeur
        shown += (kw - shown) * (1f - Math.exp(-dt * 10.0).toFloat())
        if (now - peakAt > PEAK_HOLD_MS) peak = maxOf(kw.coerceAtLeast(0f), peak - DECAY_KW_PER_S * dt)
        if (now - peakRegenAt > PEAK_HOLD_MS) peakRegen = maxOf((-kw).coerceAtLeast(0f), peakRegen - DECAY_KW_PER_S * dt)

        val labels = h >= 26 * d
        val barH = (if (labels) h * 0.42f else h * 0.6f).coerceAtMost(14 * d)
        val top = if (labels) 4 * d else (h - barH) / 2f
        val bottom = top + barH
        val pad = barH                       // place pour la pastille aux extrémités
        val x0 = pad; val x1 = w - pad
        val zero = x0 + (x1 - x0) * maxRegenKw / (maxRegenKw + maxDrawKw)
        fun xFor(v: Float) = if (v >= 0f) zero + (x1 - zero) * (v / maxDrawKw).coerceIn(0f, 1f)
            else zero - (zero - x0) * (-v / maxRegenKw).coerceIn(0f, 1f)

        paint.shader = null
        paint.color = off
        rect.set(x0, top, x1, bottom); canvas.drawRoundRect(rect, barH / 2f, barH / 2f, paint)

        val end = xFor(shown)
        if (shown > 0.05f) {
            // Bleu jusqu'à la puissance continue, orange au-delà, rouge à partir de 80 % du maximum
            val endCol = when { shown >= peakKw * 0.8f -> danger; shown > continuousKw -> heat; else -> draw }
            paint.shader = LinearGradient(zero, 0f, maxOf(end, zero + barH), 0f, intArrayOf(cold, endCol), null, Shader.TileMode.CLAMP)
            fill(zero, top, maxOf(end, zero + barH), bottom, roundRight = true); canvas.drawPath(path, paint)
        } else if (shown < -0.05f) {
            paint.shader = LinearGradient(zero, 0f, x0, 0f, intArrayOf(0x66000000 or (regen and 0xFFFFFF), regen), null, Shader.TileMode.CLAMP)
            fill(minOf(end, zero - barH), top, zero, bottom, roundRight = false); canvas.drawPath(path, paint)
        }
        paint.shader = null

        // Pastille au bout, avec un halo
        if (kotlin.math.abs(shown) > 0.05f) {
            val col = if (shown > 0f) (when { shown >= peakKw * 0.8f -> danger; shown > continuousKw -> heat; else -> draw }) else regen
            val cy = (top + bottom) / 2f
            paint.color = col; paint.alpha = 70; canvas.drawCircle(end, cy, barH * 0.95f, paint)
            paint.alpha = 255; paint.color = 0xFFFFFFFF.toInt(); canvas.drawCircle(end, cy, barH * 0.42f, paint)
        }

        // Pics des 10 dernières secondes : petit trait au-dessus de la barre
        paint.color = tick
        if (peak > 1f && peak > shown + 1f) { val px = xFor(peak); canvas.drawRect(px - d, top - 3 * d, px + d, bottom + 3 * d, paint) }
        if (peakRegen > 1f && -peakRegen < shown - 1f) {
            val px = xFor(-peakRegen); paint.color = regen; canvas.drawRect(px - d, top - 3 * d, px + d, bottom + 3 * d, paint)
        }

        // Repères discrets : puissance continue et 80 % du maximum
        paint.color = tick; paint.alpha = 110
        listOf(continuousKw, peakKw * 0.8f).filter { it < maxDrawKw }.forEach { k ->
            val px = xFor(k); canvas.drawRect(px - d * 0.6f, top, px + d * 0.6f, bottom, paint)
        }
        paint.alpha = 255

        // Repère du 0 et graduations
        paint.color = tick
        canvas.drawRect(zero - d, top - 4 * d, zero + d, bottom + 4 * d, paint)
        if (labels) {
            text.color = tick; text.textSize = labelSp * resources.displayMetrics.scaledDensity
            val ty = h - 1 * d
            text.textAlign = Paint.Align.LEFT; canvas.drawText("−${maxRegenKw.toInt()}", x0, ty, text)
            text.textAlign = Paint.Align.CENTER; canvas.drawText("0", zero, ty, text)
            text.textAlign = Paint.Align.RIGHT; canvas.drawText("+${maxDrawKw.toInt()} kW", x1, ty, text)
        }

        // Tant que la valeur bouge ou qu'un pic redescend, on redessine
        if (kotlin.math.abs(kw - shown) > 0.05f || peak > kw + 0.1f || peakRegen > -kw + 0.1f) postInvalidateOnAnimation() else lastFrame = 0L
    }
}
