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
import app.mg4dash.R

/**
 * Barres dans le style de la barre de puissance (issue #29, maquette) : rail fin arrondi, remplissage
 * en dégradé, pastille blanche avec halo au bout, repères discrets et graduations sous la barre.
 */
private class BarStyle(v: View) {
    val d = v.resources.displayMetrics.density
    val sp = v.resources.displayMetrics.scaledDensity
    val off = Themes.color(v.context, R.color.gauge_off)
    val tick = Themes.color(v.context, R.color.text_sub)
    val title = Themes.color(v.context, R.color.text_title)
    val value = Themes.color(v.context, R.color.text_value)
    val panel = Themes.color(v.context, R.color.button_idle)
    val energy = Themes.color(v.context, R.color.accent_energy)
    val cold = Themes.color(v.context, R.color.accent_cold)
    val heat = Themes.color(v.context, R.color.accent_heat)
    val danger = Themes.color(v.context, R.color.accent_danger)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val text = Paint(Paint.ANTI_ALIAS_FLAG)
    val rect = RectF()
    val path = Path()
    fun alpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)
}

/**
 * Autonomie : 0–100 %, pastille au % actuel avec une étiquette en bulle au-dessus, repère du % au
 * départ du trajet (bulle discrète) et la part du trajet en remplissage pâle entre les deux.
 * Couleur selon le niveau : vert, orange sous 30 %, rouge sous 15 % ; bleu clair → vert en recharge.
 */
class SocBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var soc = 82f; set(v) { field = v; invalidate() }
    var tripStart: Float? = 90f; set(v) { field = v; invalidate() }
    var charging = false; set(v) { field = v; invalidate() }
    /** Bulle du % actuel au-dessus de la pastille ; inutile quand le grand % est affiché juste au-dessus. */
    var currentBubble = true; set(v) { field = v; invalidate() }
    private val s = BarStyle(this)

    /** Bulle au-dessus ([below] = false, pointe en bas) ou sous la barre ([below] = true, pointe en haut). */
    private fun bubble(c: Canvas, cx: Float, anchor: Float, label: String, sizeSp: Float, fill: Int, textColor: Int, stroke: Int?, below: Boolean = false, gap: Float = 6f) {
        s.text.textSize = sizeSp * s.sp; s.text.textAlign = Paint.Align.CENTER
        s.text.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        val w = s.text.measureText(label) + 16 * s.d; val h = s.text.textSize + 8 * s.d
        val left = (cx - w / 2f).coerceIn(0f, width - w)
        if (below) s.rect.set(left, anchor + gap * s.d, left + w, anchor + gap * s.d + h)
        else s.rect.set(left, anchor - h - gap * s.d, left + w, anchor - gap * s.d)
        s.paint.shader = null; s.paint.color = fill
        c.drawRoundRect(s.rect, 8 * s.d, 8 * s.d, s.paint)
        // Pointe vers la barre
        s.path.reset()
        val tip = (gap + 0.5f) * s.d; val half = minOf(6f, gap) * s.d
        if (below) { s.path.moveTo(cx - half, anchor + tip); s.path.lineTo(cx + half, anchor + tip); s.path.lineTo(cx, anchor) }
        else { s.path.moveTo(cx - half, anchor - tip); s.path.lineTo(cx + half, anchor - tip); s.path.lineTo(cx, anchor) }
        s.path.close()
        c.drawPath(s.path, s.paint)
        if (stroke != null) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * s.d; color = stroke }
            c.drawRoundRect(s.rect, 8 * s.d, 8 * s.d, p)
        }
        s.text.color = textColor
        c.drawText(label, s.rect.centerX(), s.rect.centerY() - (s.text.descent() + s.text.ascent()) / 2f, s.text)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val d = s.d
        val barH = 14 * d
        // Bulle du % actuel au-dessus, bulle du départ du trajet en dessous : jamais l'une sur l'autre
        val top = if (currentBubble) (h - barH) / 2f else 6 * d; val bottom = top + barH
        val x0 = barH; val x1 = w - barH
        fun x(p: Float) = x0 + (x1 - x0) * (p / 100f).coerceIn(0f, 1f)
        // Une seule couleur, celle du niveau actuel (vert, orange sous 30 %, rouge sous 15 %)
        val col = when { soc < 15f -> s.danger; soc < 30f -> s.heat; else -> s.energy }
        // Rail
        s.paint.shader = null; s.paint.color = s.off
        s.rect.set(x0, top, x1, bottom); c.drawRoundRect(s.rect, barH / 2, barH / 2, s.paint)
        // Part du trajet (départ → actuel), pâle
        val start = tripStart
        if (start != null && start > soc + 0.5f) {
            s.paint.color = s.alpha(col, 70)
            s.rect.set(x(soc), top, x(start), bottom); c.drawRoundRect(s.rect, barH / 2, barH / 2, s.paint)
        }
        // Remplissage uni
        val end = x(soc)
        s.paint.color = col
        s.rect.set(x0, top, maxOf(end, x0 + barH), bottom); c.drawRoundRect(s.rect, barH / 2, barH / 2, s.paint)
        // Repères 25 / 50 / 75 %
        s.paint.color = s.tick; s.paint.alpha = 110
        listOf(25f, 50f, 75f).forEach { p -> c.drawRect(x(p) - 0.6f * d, top, x(p) + 0.6f * d, bottom, s.paint) }
        s.paint.alpha = 255
        // Départ du trajet : trait au-dessus de la barre + bulle discrète
        if (start != null && start > soc + 0.5f) {
            s.paint.color = s.tick
            c.drawRect(x(start) - d, top - 3 * d, x(start) + d, bottom + 3 * d, s.paint)
            // Bulle du % de départ, seul (#29) ; plus serrée quand la vue est basse (accueil complet)
            val label = "${Math.round(start)} %"
            if (h - bottom >= 30 * d) bubble(c, x(start), bottom + 3 * d, label, 13f, s.panel, s.title, null, below = true)
            else if (h - bottom >= 21 * d) bubble(c, x(start), bottom + 2 * d, label, 11f, s.panel, s.title, null, below = true, gap = 3f)
        }
        // Pastille au bout, avec un halo, et bulle du % actuel
        val cy = (top + bottom) / 2f
        s.paint.color = col; s.paint.alpha = 70; c.drawCircle(end, cy, barH * 0.95f, s.paint)
        s.paint.alpha = 255; s.paint.color = 0xFFFFFFFF.toInt(); c.drawCircle(end, cy, barH * 0.42f, s.paint)
        if (currentBubble) bubble(c, end, top - 3 * d, "${Math.round(soc)} %", 15f, col, 0xFF0B0F17.toInt(), null)
        // Graduations aux deux bouts, à hauteur de la barre
        s.text.typeface = null; s.text.color = s.tick; s.text.textSize = 12 * s.sp
        val ty = bottom + 15 * d
        if (ty > h) return
        if (start == null || x(start) > x0 + 90 * d) { s.text.textAlign = Paint.Align.LEFT; c.drawText("0", x0, ty, s.text) }
        if (start == null || x(start) < x1 - 140 * d) { s.text.textAlign = Paint.Align.RIGHT; c.drawText("100 %", x1, ty, s.text) }
    }
}
