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
 * Bars in the style of the power bar (issue #29, mockup): thin rounded rail, gradient
 * fill, white dot with a halo at the end, discreet markers and ticks under the bar.
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
 * Range: 0–100 %, dot at the current % with a bubble label above, marker of the % at the
 * start of the trip (discreet bubble) and the trip's share as a pale fill between the two.
 * Colour by level: green, orange below 30 %, red below 15 %; light blue → green when charging.
 */
class SocBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var soc = 82f; set(v) { if (field != v) { field = v; invalidate() } }
    var tripStart: Float? = 90f; set(v) { if (field != v) { field = v; invalidate() } }
    var charging = false; set(v) { if (field != v) { field = v; invalidate() } }
    /** Current % bubble above the dot; useless when the large % is shown just above. */
    var currentBubble = true; set(v) { if (field != v) { field = v; invalidate() } }
    /** Size of the small start bubble and ticks (sp), larger in the "power" card (#29). */
    var smallSp = 11f; set(v) { field = v; invalidate() }
    var tickSp = 12f; set(v) { field = v; invalidate() }
    private val s = BarStyle(this)

    /** Bubble above ([below] = false, tip down) or under the bar ([below] = true, tip up). */
    private fun bubble(c: Canvas, cx: Float, anchor: Float, label: String, sizeSp: Float, fill: Int, textColor: Int, stroke: Int?, below: Boolean = false, gap: Float = 6f) {
        s.text.textSize = sizeSp * s.sp; s.text.textAlign = Paint.Align.CENTER
        s.text.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        val w = s.text.measureText(label) + 16 * s.d; val h = s.text.textSize + 8 * s.d
        val left = (cx - w / 2f).coerceIn(0f, width - w)
        if (below) s.rect.set(left, anchor + gap * s.d, left + w, anchor + gap * s.d + h)
        else s.rect.set(left, anchor - h - gap * s.d, left + w, anchor - gap * s.d)
        s.paint.shader = null; s.paint.color = fill
        c.drawRoundRect(s.rect, 8 * s.d, 8 * s.d, s.paint)
        // Tip towards the bar
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
        // Current % bubble above, trip start bubble below: never one on the other
        val top = if (currentBubble) (h - barH) / 2f else 6 * d; val bottom = top + barH
        val x0 = barH; val x1 = w - barH
        fun x(p: Float) = x0 + (x1 - x0) * (p / 100f).coerceIn(0f, 1f)
        // A single colour, that of the current level (green, orange below 30 %, red below 15 %)
        val col = when { soc < 15f -> s.danger; soc < 30f -> s.heat; else -> s.energy }
        // Rail
        s.paint.shader = null; s.paint.color = s.off
        s.rect.set(x0, top, x1, bottom); c.drawRoundRect(s.rect, barH / 2, barH / 2, s.paint)
        // Trip's share (start → current), pale
        val start = tripStart
        if (start != null && start > soc + 0.5f) {
            s.paint.color = s.alpha(col, 70)
            s.rect.set(x(soc), top, x(start), bottom); c.drawRoundRect(s.rect, barH / 2, barH / 2, s.paint)
        }
        // Remplissage uni
        val end = x(soc)
        s.paint.color = col
        s.rect.set(x0, top, maxOf(end, x0 + barH), bottom); c.drawRoundRect(s.rect, barH / 2, barH / 2, s.paint)
        // 25 / 50 / 75 % markers
        s.paint.color = s.tick; s.paint.alpha = 110
        listOf(25f, 50f, 75f).forEach { p -> c.drawRect(x(p) - 0.6f * d, top, x(p) + 0.6f * d, bottom, s.paint) }
        s.paint.alpha = 255
        // Trip start: line above the bar + discreet bubble
        if (start != null && start > soc + 0.5f) {
            s.paint.color = s.tick
            c.drawRect(x(start) - d, top - 3 * d, x(start) + d, bottom + 3 * d, s.paint)
            // Start % bubble, alone (#29); tighter when the view is low (full home)
            val label = "${Math.round(start)} %"
            // Twice as large (#29): 22 sp, reduced only if room is short
            val room = (h - bottom) / d
            if (room >= 36) bubble(c, x(start), bottom + 3 * d, label, 22f, s.panel, s.title, null, below = true)
            else if (room >= smallSp + 10) bubble(c, x(start), bottom + 2 * d, label, smallSp, s.panel, s.title, null, below = true, gap = 3f)
        }
        // Dot at the end, with a halo, and current % bubble
        val cy = (top + bottom) / 2f
        s.paint.color = col; s.paint.alpha = 70; c.drawCircle(end, cy, barH * 0.95f, s.paint)
        s.paint.alpha = 255; s.paint.color = 0xFFFFFFFF.toInt(); c.drawCircle(end, cy, barH * 0.42f, s.paint)
        if (currentBubble) bubble(c, end, top - 3 * d, "${Math.round(soc)} %", 15f, col, 0xFF0B0F17.toInt(), null)
        // No "0" or "100 %" at the ends: the bar's ends are enough (#29)
    }
}
