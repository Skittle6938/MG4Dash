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
            val label = "${Math.round(start)}"
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

/**
 * Bubble in the bars' style, placed in [r]: above [anchor] (tip down) or under it ([below], tip up),
 * kept inside the view's width. Measured only when [c] is null.
 */
private fun BarStyle.bubble(c: Canvas?, width: Int, cx: Float, anchor: Float, label: String, sizeSp: Float, fill: Int, textColor: Int,
                            r: RectF, below: Boolean = false, gap: Float = 6f) {
    text.textSize = sizeSp * sp; text.textAlign = Paint.Align.CENTER
    text.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    val w = text.measureText(label) + 16 * d; val h = text.textSize + 8 * d
    val left = (cx - w / 2f).coerceIn(0f, maxOf(0f, width - w))
    if (below) r.set(left, anchor + gap * d, left + w, anchor + gap * d + h) else r.set(left, anchor - h - gap * d, left + w, anchor - gap * d)
    if (c == null) return
    paint.shader = null; paint.color = fill
    c.drawRoundRect(r, 8 * d, 8 * d, paint)
    path.reset()
    val tip = (gap + 0.5f) * d; val half = minOf(6f, gap) * d
    if (below) { path.moveTo(cx - half, anchor + tip); path.lineTo(cx + half, anchor + tip); path.lineTo(cx, anchor) }
    else { path.moveTo(cx - half, anchor - tip); path.lineTo(cx + half, anchor - tip); path.lineTo(cx, anchor) }
    path.close()
    c.drawPath(path, paint)
    text.color = textColor
    c.drawText(label, r.centerX(), r.centerY() - (text.descent() + text.ascent()) / 2f, text)
}

/**
 * 4G data over 30 days, in the style of the home's battery bar: rail, plain fill, dot with a halo
 * and the amount in a bubble above. Green under the alert threshold, orange above, red beyond MG's
 * limit (1 GB). The scale is 1 GB, and grows to 2, 5 GB… when the amount or the threshold exceed it;
 * MG's limit is then marked above the bar. Its end shows the scale ("2 Go"), with no other word.
 */
class QuotaBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var used = 0L; set(v) { if (field != v) { field = v; invalidate() } }
    var threshold = app.mg4dash.system.DataUsage.DEFAULT_THRESHOLD; set(v) { if (field != v) { field = v; invalidate() } }
    private val s = BarStyle(this)
    private val rCur = RectF(); private val rThr = RectF(); private val rLim = RectF(); private val rEnd = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize((92 * s.d).toInt(), heightMeasureSpec))
    }

    override fun onDraw(c: Canvas) {
        val du = app.mg4dash.system.DataUsage
        val w = width.toFloat(); val d = s.d
        val barH = 14 * d
        val top = 38 * d; val bottom = top + barH
        val x0 = barH; val x1 = w - barH
        val scale = du.scale(maxOf(used, threshold))
        fun x(v: Long) = x0 + (x1 - x0) * (v.toFloat() / scale).coerceIn(0f, 1f)
        val col = when { used >= du.MG_LIMIT -> s.danger; used >= threshold -> s.heat; else -> s.energy }
        // Rail and fill
        s.paint.shader = null; s.paint.color = s.off
        s.rect.set(x0, top, x1, bottom); c.drawRoundRect(s.rect, barH / 2, barH / 2, s.paint)
        val end = x(used)
        s.paint.color = col
        s.rect.set(x0, top, maxOf(end, x0 + barH), bottom); c.drawRoundRect(s.rect, barH / 2, barH / 2, s.paint)
        // 25 / 50 / 75 % markers
        s.paint.color = s.tick; s.paint.alpha = 110
        listOf(0.25f, 0.5f, 0.75f).forEach { p -> val px = x0 + (x1 - x0) * p; c.drawRect(px - 0.6f * d, top, px + 0.6f * d, bottom, s.paint) }
        s.paint.alpha = 255
        // Where the labels go: the amount above, the threshold below; MG's limit above if free, otherwise below
        val curLabel = du.format(context, used)
        s.bubble(null, width, end, top - 3 * d, curLabel, 15f, col, 0, rCur)
        val thrLabel = context.getString(app.mg4dash.R.string.du_alert_at, du.formatRound(context, threshold))
        s.bubble(null, width, x(threshold), bottom + 3 * d, thrLabel, 15f, s.panel, s.heat, rThr, below = true)
        val showLimit = scale > du.MG_LIMIT
        val limLabel = context.getString(app.mg4dash.R.string.du_mg_limit, du.formatRound(context, du.MG_LIMIT))
        var limBelow = false
        if (showLimit) {
            s.bubble(null, width, x(du.MG_LIMIT), top - 3 * d, limLabel, 15f, s.panel, s.danger, rLim)
            if (RectF.intersects(rLim, rCur)) {
                s.bubble(null, width, x(du.MG_LIMIT), bottom + 3 * d, limLabel, 15f, s.panel, s.danger, rLim, below = true)
                limBelow = true
            }
        }
        val limitLabelShown = showLimit && !(limBelow && RectF.intersects(rLim, rThr))
        // Threshold and MG's limit: lines across the bar
        s.paint.color = s.heat; c.drawRect(x(threshold) - d, top - 3 * d, x(threshold) + d, bottom + 3 * d, s.paint)
        if (showLimit) { s.paint.color = s.danger; c.drawRect(x(du.MG_LIMIT) - d, top - 3 * d, x(du.MG_LIMIT) + d, bottom + 3 * d, s.paint) }
        s.bubble(c, width, x(threshold), bottom + 3 * d, thrLabel, 15f, s.panel, s.heat, rThr, below = true)
        if (limitLabelShown) s.bubble(c, width, x(du.MG_LIMIT), if (limBelow) bottom + 3 * d else top - 3 * d, limLabel, 15f, s.panel, s.danger, rLim, below = limBelow)
        // Dot at the end, with a halo, and the amount's bubble
        val cy = (top + bottom) / 2f
        s.paint.color = col; s.paint.alpha = 70; c.drawCircle(end, cy, barH * 0.95f, s.paint)
        s.paint.alpha = 255; s.paint.color = 0xFFFFFFFF.toInt(); c.drawCircle(end, cy, barH * 0.42f, s.paint)
        s.bubble(c, width, end, top - 3 * d, curLabel, 15f, col, 0xFF0B0F17.toInt(), rCur)
        // Scale at the end, under the bar, unless a label is already there
        s.text.textSize = 14 * s.sp; s.text.typeface = android.graphics.Typeface.DEFAULT; s.text.textAlign = Paint.Align.RIGHT
        val endLabel = du.formatRound(context, scale)
        val ew = s.text.measureText(endLabel)
        rEnd.set(w - ew - 4 * d, bottom + 6 * d, w, bottom + 6 * d + s.text.textSize + 4 * d)
        if (!RectF.intersects(rEnd, rThr) && !(limitLabelShown && limBelow && RectF.intersects(rEnd, rLim))) {
            s.text.color = s.title
            c.drawText(endLabel, w, bottom + 9 * d - s.text.ascent(), s.text)
        }
    }
}

/** Share of one use in the total: thin rail, plain fill in the use's colour. */
class ShareBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var fraction = 0f; set(v) { if (field != v) { field = v; invalidate() } }
    var color = Themes.color(context, R.color.accent_cold); set(v) { if (field != v) { field = v; invalidate() } }
    private val s = BarStyle(this)

    override fun onDraw(c: Canvas) {
        val h = 10 * s.d; val top = (height - h) / 2f
        s.paint.shader = null; s.paint.color = s.off
        s.rect.set(0f, top, width.toFloat(), top + h); c.drawRoundRect(s.rect, h / 2, h / 2, s.paint)
        if (fraction <= 0f) return
        s.paint.color = color
        s.rect.set(0f, top, maxOf(h, width * fraction.coerceIn(0f, 1f)), top + h); c.drawRoundRect(s.rect, h / 2, h / 2, s.paint)
    }
}

/**
 * One bar per day over the last 30 days, stacked by use (colours of [DataUsage.Use]); a touch on a
 * bar gives its index to [onDayTap]. Dashed lines at round amounts, day numbers every five days.
 */
class DailyBars @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var days: List<app.mg4dash.system.DataUsage.Day> = emptyList(); set(v) { field = v; invalidate() }
    var selected = -1; set(v) { if (field != v) { field = v; invalidate() } }
    /** Networks counted in the bars: the car's 4G, the Wi-Fi client, or both. */
    var net4g = true; set(v) { if (field != v) { field = v; invalidate() } }
    var netWifi = false; set(v) { if (field != v) { field = v; invalidate() } }
    var onDayTap: ((Int) -> Unit)? = null
    private val s = BarStyle(this)
    private val colors = app.mg4dash.system.DataUsage.Use.values().map { Themes.color(context, it.colorRes) }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = s.d; pathEffect = android.graphics.DashPathEffect(floatArrayOf(4 * s.d, 4 * s.d), 0f)
    }
    private val left get() = 64 * s.d
    private val labelsH get() = 26 * s.d

    private val gestures = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: android.view.MotionEvent) = true
        override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
            if (days.isEmpty() || e.x < left) return false
            val i = ((e.x - left) / (width - left) * days.size).toInt().coerceIn(0, days.size - 1)
            onDayTap?.invoke(i)
            return true
        }
    })

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean = gestures.onTouchEvent(event) || super.onTouchEvent(event)

    /** Round step for the dashed lines: 1, 2, 5, 10, 20, 50… MB, about three lines. */
    private fun step(max: Long): Long {
        val mb = 1_000_000L
        var st = mb
        val seq = longArrayOf(1, 2, 5)
        var k = 0; var mul = 1L
        while (max / st > 3) { k++; if (k % 3 == 0) mul *= 10; st = seq[k % 3] * mul * mb }
        return st
    }

    override fun onDraw(c: Canvas) {
        if (days.isEmpty()) return
        val d = s.d
        val bottom = height - labelsH
        val top = 8 * d
        val values = days.map { it.of(net4g, netWifi) }
        val maxDay = values.maxOf { it.sum() }.coerceAtLeast(5_000_000L)
        val st = step(maxDay)
        val max = maxOf(maxDay, st * ((maxDay + st - 1) / st)).toFloat()
        fun y(v: Long) = bottom - (bottom - top) * (v / max)
        // Dashed lines and their amounts
        s.text.textSize = 13 * s.sp; s.text.textAlign = Paint.Align.LEFT; s.text.typeface = android.graphics.Typeface.DEFAULT
        s.text.color = s.title
        dash.color = s.alpha(s.tick, 90)
        var v = 0L
        while (v <= max) {
            val yy = y(v)
            c.drawLine(left, yy, width.toFloat(), yy, dash)
            c.drawText(app.mg4dash.system.DataUsage.formatRound(context, v).takeIf { v > 0 } ?: "0", 0f, yy - s.text.ascent() / 2f, s.text)
            v += st
        }
        // Bars
        val slot = (width - left) / days.size
        val gap = minOf(6 * d, slot * 0.25f)
        days.forEachIndexed { i, day ->
            val l = left + i * slot + gap / 2; val r = l + slot - gap
            var acc = 0L
            val bytes = values[i]
            val n = bytes.size
            val lastSeg = (n - 1 downTo 0).firstOrNull { bytes[it] > 0 } ?: -1
            for (k in 0 until n) {
                val b = bytes[k]; if (b <= 0) continue
                val y0 = y(acc); acc += b; val y1 = minOf(y(acc), y0 - 1.5f * d)
                s.paint.color = colors[k]
                if (i == selected || selected < 0) s.paint.alpha = 255 else s.paint.alpha = 110
                if (k == lastSeg) {
                    // Rounded top, square bottom
                    s.rect.set(l, y1, r, y0); c.drawRoundRect(s.rect, 4 * d, 4 * d, s.paint)
                    c.drawRect(l, minOf(y0, y1 + 4 * d), r, y0, s.paint)
                } else c.drawRect(l, y1, r, y0, s.paint)
            }
            s.paint.alpha = 255
            // Day number every five days and on the 1st
            val date = day.date % 100
            if (date == 1 || date % 5 == 0) {
                s.text.textAlign = Paint.Align.CENTER; s.text.textSize = 14 * s.sp
                s.text.color = if (i == selected) s.value else s.title
                c.drawText(date.toString(), (l + r) / 2f, bottom + 6 * d - s.text.ascent(), s.text)
            }
        }
    }
}
