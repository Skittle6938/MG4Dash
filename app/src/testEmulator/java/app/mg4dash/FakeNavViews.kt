package app.mg4dash

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/**
 * Demonstration views for README screenshots: what a third party app can look like
 * shown in the map (electric trip planner, guidance). Generic drawings, with no
 * logo or brand, on a background of fictional streets.
 */
abstract class FakeAppView(context: Context) : View(context) {
    protected val d = resources.displayMetrics.density
    protected val p = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Fictional street plan (slightly irregular grid, park, river). */
    protected fun streets(c: Canvas, dark: Boolean) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawColor(if (dark) 0xFF1E2430.toInt() else 0xFFEDEBE6.toInt())
        p.style = Paint.Style.FILL
        p.color = if (dark) 0xFF1F3A2C.toInt() else 0xFFCFE6C4.toInt()
        c.drawRoundRect(RectF(w * 0.08f, h * 0.55f, w * 0.38f, h * 0.78f), 30f, 30f, p)
        p.color = if (dark) 0xFF1B3550.toInt() else 0xFFAFD3EE.toInt()
        val river = Path().apply { moveTo(0f, h * 0.2f); cubicTo(w * 0.3f, h * 0.28f, w * 0.5f, h * 0.05f, w, h * 0.12f); lineTo(w, h * 0.17f); cubicTo(w * 0.5f, h * 0.1f, w * 0.3f, h * 0.33f, 0f, h * 0.25f); close() }
        c.drawPath(river, p)
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
        p.color = if (dark) 0xFF323B4B.toInt() else Color.WHITE
        var i = 0
        var x = -40f
        while (x < w + 40) { p.strokeWidth = if (i % 3 == 0) 14f * d / 2 else 7f * d / 2; c.drawLine(x, 0f, x + 50f, h, p); x += 95f * d / 2; i++ }
        var y = 20f; i = 0
        while (y < h + 40) { p.strokeWidth = if (i % 3 == 1) 14f * d / 2 else 7f * d / 2; c.drawLine(0f, y, w, y - 30f, p); y += 85f * d / 2; i++ }
        p.style = Paint.Style.FILL
    }

    /** Route line with a white border, as in navigation apps. */
    protected fun routeLine(c: Canvas, path: Path, color: Int, widthDp: Float) {
        p.style = Paint.Style.STROKE; p.strokeJoin = Paint.Join.ROUND; p.strokeCap = Paint.Cap.ROUND
        p.color = Color.WHITE; p.strokeWidth = (widthDp + 4) * d; c.drawPath(path, p)
        p.color = color; p.strokeWidth = widthDp * d; c.drawPath(path, p)
        p.style = Paint.Style.FILL
    }

    protected fun text(c: Canvas, s: String, x: Float, y: Float, sp: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) {
        p.textSize = sp * resources.displayMetrics.scaledDensity; p.color = color; p.textAlign = align
        p.typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        c.drawText(s, x, y, p); p.textAlign = Paint.Align.LEFT
    }
}

/** Electric planner: route, charging stop, battery % at each step. */
class FakePlannerView(context: Context) : FakeAppView(context) {
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        // OpenStreetMap map underneath, darkened like a planner in dark theme
        p.style = Paint.Style.FILL; p.color = 0x8C0B0F17.toInt(); c.drawRect(0f, 0f, w, h, p)
        // Route along main roads (polylines, rounded corners)
        val route = Path().apply {
            moveTo(w * 0.12f, h * 0.88f); lineTo(w * 0.2f, h * 0.7f); lineTo(w * 0.33f, h * 0.62f); lineTo(w * 0.42f, h * 0.47f)
            lineTo(w * 0.5f, h * 0.42f); lineTo(w * 0.62f, h * 0.36f); lineTo(w * 0.7f, h * 0.22f); lineTo(w * 0.86f, h * 0.12f)
        }
        routeLine(c, route, 0xFF2E7DFF.toInt(), 8f)
        fun stop(x: Float, y: Float, col: Int, label: String, sub: String, left: Boolean = false) {
            p.color = Color.WHITE; c.drawCircle(x, y, 13 * d, p); p.color = col; c.drawCircle(x, y, 9 * d, p)
            val r = if (left) RectF(x - 150 * d, y - 4 * d, x - 16 * d, y + 44 * d) else RectF(x + 16 * d, y - 26 * d, x + 150 * d, y + 22 * d)
            p.color = 0xE6141A26.toInt(); c.drawRoundRect(r, 10 * d, 10 * d, p)
            text(c, label, r.left + 10 * d, r.top + 20 * d, 15f, Color.WHITE, bold = true)
            text(c, sub, r.left + 10 * d, r.top + 39 * d, 12f, 0xFFA0AEC0.toInt())
        }
        stop(w * 0.12f, h * 0.88f, 0xFF00E676.toInt(), "Start · 82 %", "14:05")
        stop(w * 0.5f, h * 0.42f, 0xFFFFB300.toInt(), "Charge 18 min", "24 % → 61 %")
        stop(w * 0.86f, h * 0.12f, 0xFF2E7DFF.toInt(), "Arrival · 19 %", "17:40", left = true)
        // Bottom banner: trip summary
        val bar = RectF(0f, h - 70 * d, w, h)
        p.color = 0xF0141A26.toInt(); c.drawRect(bar, p)
        text(c, "3 h 35", 16 * d, h - 40 * d, 20f, Color.WHITE, bold = true)
        text(c, "312 km · 1 stop", 16 * d, h - 16 * d, 13f, 0xFFA0AEC0.toInt())
        text(c, "Arrival 19 %", w - 16 * d, h - 40 * d, 18f, 0xFF00E676.toInt(), bold = true, align = Paint.Align.RIGHT)
        text(c, "Expected 16.8 kWh/100", w - 16 * d, h - 16 * d, 13f, 0xFFA0AEC0.toInt(), align = Paint.Align.RIGHT)
    }
}

/** Guidance: direction arrow, street name, route, time and distance remaining. */
class FakeGuidanceView(context: Context) : FakeAppView(context) {
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        // OpenStreetMap map underneath; route, then the app's banners
        val route = Path().apply { moveTo(w * 0.5f, h); lineTo(w * 0.5f, h * 0.33f); lineTo(w * 0.3f, h * 0.27f); lineTo(w * 0.1f, h * 0.24f) }
        routeLine(c, route, 0xFF2F80ED.toInt(), 10f)
        // Speed limit
        p.style = Paint.Style.FILL; p.color = Color.WHITE; c.drawCircle(w - 42 * d, h - 100 * d, 26 * d, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 5 * d; p.color = 0xFFE53935.toInt(); c.drawCircle(w - 42 * d, h - 100 * d, 23 * d, p)
        p.style = Paint.Style.FILL
        text(c, "50", w - 42 * d, h - 92 * d, 20f, 0xFF1D2433.toInt(), bold = true, align = Paint.Align.CENTER)
        // Car position
        // (placed on the map's position marker, in the centre)
        val cx = w * 0.5f; val cy = h * 0.5f
        val car = Path().apply { moveTo(cx, cy - 14 * d); lineTo(cx + 11 * d, cy + 12 * d); lineTo(cx, cy + 6 * d); lineTo(cx - 11 * d, cy + 12 * d); close() }
        p.color = Color.WHITE; c.drawCircle(cx, cy, 22 * d, p); p.color = 0xFFE53935.toInt(); c.drawPath(car, p)
        // Direction banner
        val top = RectF(10 * d, 10 * d, w - 70 * d, 90 * d)
        p.color = 0xFF2F80ED.toInt(); c.drawRoundRect(top, 14 * d, 14 * d, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 5 * d; p.color = Color.WHITE
        val arrow = Path().apply { moveTo(top.left + 40 * d, top.bottom - 20 * d); lineTo(top.left + 40 * d, top.top + 32 * d); lineTo(top.left + 22 * d, top.top + 32 * d) }
        c.drawPath(arrow, p); p.style = Paint.Style.FILL
        val head = Path().apply { moveTo(top.left + 14 * d, top.top + 32 * d); lineTo(top.left + 26 * d, top.top + 22 * d); lineTo(top.left + 26 * d, top.top + 42 * d); close() }
        c.drawPath(head, p)
        text(c, "150 m", top.left + 64 * d, top.top + 34 * d, 20f, Color.WHITE, bold = true)
        text(c, "Allée Thomy Thierry", top.left + 64 * d, top.top + 62 * d, 17f, Color.WHITE)
        // Bottom banner: arrival, duration, distance
        val bar = RectF(0f, h - 64 * d, w, h)
        p.color = Color.WHITE; c.drawRect(bar, p)
        text(c, "18:27", 16 * d, h - 32 * d, 20f, 0xFF1D2433.toInt(), bold = true)
        text(c, "Arrival", 16 * d, h - 12 * d, 12f, 0xFF6B7280.toInt())
        text(c, "26 min", w / 2, h - 32 * d, 20f, 0xFF1D2433.toInt(), bold = true, align = Paint.Align.CENTER)
        text(c, "Duration", w / 2, h - 12 * d, 12f, 0xFF6B7280.toInt(), align = Paint.Align.CENTER)
        text(c, "22 km", w - 16 * d, h - 32 * d, 20f, 0xFF1D2433.toInt(), bold = true, align = Paint.Align.RIGHT)
        text(c, "Distance", w - 16 * d, h - 12 * d, 12f, 0xFF6B7280.toInt(), align = Paint.Align.RIGHT)
    }
}
