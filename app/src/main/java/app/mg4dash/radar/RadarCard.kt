package app.mg4dash.radar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import app.mg4dash.R
import app.mg4dash.system.Prefs
import app.mg4dash.system.Units
import app.mg4dash.ui.Themes
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The alerter's card (test): the speed and the limit sign in a small place; with the zone bar and the next zone in a
 * medium one; in a wide or tall one, a dial with the limit marked in red, the sign, and the current zone, the next
 * limit and the zones ahead. White as long as the speed is under the limit, orange a little over, red well over.
 */
class RadarCard(context: Context) : View(context) {
    enum class Form { SMALL, MEDIUM, FULL }

    var form = Form.MEDIUM
        set(v) { field = v; invalidate() }

    private var s = RadarRepo.State()

    fun show(state: RadarRepo.State) { if (state != s) { s = state; invalidate() } }

    /** A preparation running (its progress) and tiles downloaded live: a thin bar at the bottom of the card. */
    private var prep: RadarRepo.Prep? = null
    private var live = 0
    fun downloads(prep: RadarRepo.Prep?, live: Int) {
        val p = prep?.takeIf { it.running }
        if (p != this.prep || live != this.live) { this.prep = p; this.live = live; invalidate() }
    }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val bold = Typeface.create("sans-serif", Typeface.BOLD)
    private fun col(res: Int) = Themes.color(context, res)
    private val d = resources.displayMetrics.density

    private val miles get() = Units.miles(context)

    /** Colour of the speed: white, orange (light), red (strong); always white with the visual alerts off. */
    private fun levelColor(): Int = when {
        !Prefs(context).radarVisual -> col(R.color.text_value)
        s.level == Level.STRONG -> col(R.color.accent_danger)
        s.level == Level.LIGHT -> col(R.color.accent_heat)
        else -> col(R.color.text_value)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val pad = 22 * d
        when (form) {
            Form.SMALL -> drawSmall(c, RectF(pad, pad * 0.8f, w - pad, h - pad * 1.2f))
            Form.MEDIUM -> {
                val top = RectF(pad, pad * 0.8f, w - pad, h * 0.6f)
                drawSmall(c, top, status = false)
                drawZoneBar(c, RectF(pad, h * 0.6f + 6 * d, w - pad, h - pad * 1.2f))
            }
            Form.FULL -> if (w > h * 1.3f) {
                val side = min(h - pad * 2, w * 0.45f)
                drawDial(c, RectF(pad, (h - side) / 2, pad + side, (h + side) / 2))
                drawRows(c, RectF(pad * 2 + side, pad, w - pad, h - pad * 1.2f))
            } else {
                val side = min(w - pad * 2, h * 0.5f)
                drawDial(c, RectF((w - side) / 2, pad, (w + side) / 2, pad + side))
                drawRows(c, RectF(pad, pad * 1.5f + side, w - pad, h - pad * 1.2f))
            }
        }
        drawDownload(c, w, h)
    }

    private fun speedText(): String = s.speedKmh?.let { Speeds.toUnit(it, miles).toString() } ?: "—"

    /** Speed on the left, sign on the right, the state under them. */
    private fun drawSmall(c: Canvas, r: RectF, status: Boolean = true) {
        val sign = min(r.height() * (if (status) 0.78f else 0.9f), r.width() * 0.42f)
        val cy = r.top + (if (status) r.height() * 0.42f else r.height() / 2)
        drawSign(c, r.right - sign / 2, cy, sign / 2)
        p.textAlign = Paint.Align.LEFT; p.typeface = medium; p.color = levelColor()
        p.textSize = sign * 0.72f
        c.drawText(speedText(), r.left, cy + p.textSize * 0.36f, p)
        val sw = p.measureText(speedText())
        p.typeface = Typeface.DEFAULT; p.textSize = 24 * d; p.color = col(R.color.text_sub)
        c.drawText(Units.speedUnit(context), r.left + sw + 10 * d, cy + sign * 0.26f, p)
        if (status) {
            p.textSize = 25 * d; p.color = if (s.zone != null) col(R.color.text_value) else col(R.color.text_sub)
            c.drawText(ellipsize(statusLine(), r.width()), r.left, r.bottom - 2 * d, p)
        }
    }

    /** The limit sign: red ring with the limit, grey when estimated or unknown. */
    private fun drawSign(c: Canvas, cx: Float, cy: Float, rad: Float) {
        val limit = s.zone?.limit ?: s.limit
        val known = limit != null && s.status == RadarRepo.Status.OK
        val grey = !known || (s.zone == null && s.estimated)
        p.style = Paint.Style.FILL; p.color = 0xFFFFFFFF.toInt()
        c.drawCircle(cx, cy, rad, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = rad * 0.2f; p.color = if (grey) 0xFF8A8F98.toInt() else 0xFFD32F2F.toInt()
        c.drawCircle(cx, cy, rad - p.strokeWidth / 2, p)
        p.style = Paint.Style.FILL; p.color = if (grey) 0xFF5F6670.toInt() else 0xFF111111.toInt()
        p.textAlign = Paint.Align.CENTER; p.typeface = bold
        val txt = if (known) Speeds.toUnit(limit!!.toFloat(), miles).toString() else "?"
        p.textSize = rad * (if (txt.length >= 3) 0.78f else 0.95f)
        c.drawText(txt, cx, cy + p.textSize * 0.36f, p)
        if (known && s.fromCar) {
            // The car's own limit (ISA), OSM having none
            p.typeface = bold; p.textSize = rad * 0.26f; p.color = 0xFF111111.toInt()
            c.drawText(context.getString(R.string.rad_isa), cx, cy + rad * 0.64f, p)
        } else if (known && grey) {
            p.typeface = Typeface.DEFAULT; p.textSize = rad * 0.24f
            c.drawText(context.getString(R.string.rad_estimated), cx, cy + rad * 0.62f, p)
        }
    }

    /** The zone the car is in (red frame, its end) or the next one ahead. */
    private fun drawZoneBar(c: Canvas, r: RectF) {
        val z = s.zone
        val rr = 18 * d
        p.style = Paint.Style.FILL
        p.color = if (z != null) (col(R.color.accent_danger) and 0x00FFFFFF) or 0x33000000 else (col(R.color.text_sub) and 0x00FFFFFF) or 0x1A000000
        c.drawRoundRect(r, rr, rr, p)
        if (z != null) {
            p.style = Paint.Style.STROKE; p.strokeWidth = 2 * d; p.color = col(R.color.accent_danger)
            c.drawRoundRect(r, rr, rr, p); p.style = Paint.Style.FILL
        }
        p.textAlign = Paint.Align.LEFT; p.typeface = medium; p.textSize = 27 * d
        p.color = col(R.color.text_value)
        val x = r.left + 18 * d
        val line1 = if (z != null) zoneTitle(z.kind, z.limit) else s.next.firstOrNull()?.let { context.getString(R.string.rad_next_zone, kindName(context, it.kind), distText(context, it.dist)) } ?: statusLine()
        c.drawText(ellipsize(line1, r.width() - 36 * d), x, r.top + r.height() * 0.42f, p)
        p.typeface = Typeface.DEFAULT; p.textSize = 24 * d; p.color = col(R.color.text_sub)
        val line2 = if (z != null) context.getString(R.string.rad_zone_end, distText(context, z.toEnd))
            else s.nextLimit?.let { (dist, lim) -> context.getString(R.string.rad_next_limit, limitText(lim), distText(context, dist)) } ?: ""
        c.drawText(ellipsize(line2, r.width() - 36 * d), x, r.top + r.height() * 0.78f, p)
    }

    /** Dial: 240° arc, the part up to the speed filled, a red mark at the limit, the speed in the middle. */
    private fun drawDial(c: Canvas, r: RectF) {
        val limit = s.zone?.limit ?: s.limit
        val speed = s.speedKmh ?: 0f
        val max = maxOf((limit ?: 90) * 1.4f, speed * 1.1f, 60f)
        val stroke = r.width() * 0.07f
        val arc = RectF(r.left + stroke, r.top + stroke, r.right - stroke, r.bottom - stroke)
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = stroke
        p.color = (col(R.color.text_sub) and 0x00FFFFFF) or 0x40000000
        c.drawArc(arc, 150f, 240f, false, p)
        p.color = levelColor()
        c.drawArc(arc, 150f, 240f * (speed / max).coerceIn(0f, 1f), false, p)
        p.strokeCap = Paint.Cap.BUTT
        if (limit != null && s.status == RadarRepo.Status.OK) {
            val a = Math.toRadians((150.0 + 240.0 * (limit / max))).toFloat()
            val cx = arc.centerX(); val cy = arc.centerY(); val rad = arc.width() / 2
            p.color = 0xFFD32F2F.toInt(); p.strokeWidth = 5 * d
            c.drawLine(cx + cos(a) * (rad - stroke), cy + sin(a) * (rad - stroke), cx + cos(a) * (rad + stroke), cy + sin(a) * (rad + stroke), p)
        }
        p.style = Paint.Style.FILL; p.textAlign = Paint.Align.CENTER
        p.typeface = medium; p.color = levelColor(); p.textSize = r.width() * 0.3f
        c.drawText(speedText(), r.centerX(), r.centerY() + p.textSize * 0.3f, p)
        p.typeface = Typeface.DEFAULT; p.textSize = r.width() * 0.08f; p.color = col(R.color.text_sub)
        c.drawText(Units.speedUnit(context), r.centerX(), r.centerY() + r.width() * 0.28f, p)
    }

    /** Sign on top, then the zone, the next limit and the zones ahead. */
    private fun drawRows(c: Canvas, r: RectF) {
        val sign = min(r.height() * 0.34f, r.width() * 0.4f)
        drawSign(c, r.left + sign / 2, r.top + sign / 2, sign / 2)
        p.textAlign = Paint.Align.LEFT; p.typeface = medium; p.textSize = 28 * d; p.color = col(R.color.text_value)
        val tx = r.left + sign + 20 * d
        c.drawText(ellipsize(if (s.status == RadarRepo.Status.OK) (if (s.zone == null && s.estimated) context.getString(R.string.rad_limit_estimated) else context.getString(R.string.rad_limit)) else statusLine(), r.right - tx), tx, r.top + sign * 0.45f, p)
        p.typeface = Typeface.DEFAULT; p.textSize = 24 * d; p.color = col(R.color.text_sub)
        s.nextLimit?.let { (dist, lim) -> c.drawText(ellipsize(context.getString(R.string.rad_next_limit, limitText(lim), distText(context, dist)), r.right - tx), tx, r.top + sign * 0.8f, p) }
        val rows = ArrayList<Pair<String, Boolean>>()
        s.zone?.let { z -> rows += (zoneTitle(z.kind, z.limit) + " · " + context.getString(R.string.rad_zone_end, distText(context, z.toEnd))) to true }
        s.next.forEach { n -> rows += context.getString(R.string.rad_next_zone, kindName(context, n.kind) + (n.limit?.let { " " + limitText(it) } ?: ""), distText(context, n.dist)) to false }
        if (rows.isEmpty() && s.status == RadarRepo.Status.OK) rows += context.getString(R.string.rad_no_zone) to false
        val rowH = 64 * d
        var y = r.top + sign + 16 * d
        for ((text, zone) in rows) {
            if (y + rowH > r.bottom + 4 * d) break
            val box = RectF(r.left, y, r.right, y + rowH - 8 * d)
            p.style = Paint.Style.FILL
            p.color = if (zone) (col(R.color.accent_danger) and 0x00FFFFFF) or 0x33000000 else (col(R.color.text_sub) and 0x00FFFFFF) or 0x1A000000
            c.drawRoundRect(box, 14 * d, 14 * d, p)
            if (zone) { p.style = Paint.Style.STROKE; p.strokeWidth = 2 * d; p.color = col(R.color.accent_danger); c.drawRoundRect(box, 14 * d, 14 * d, p); p.style = Paint.Style.FILL }
            p.textSize = 25 * d; p.typeface = if (zone) medium else Typeface.DEFAULT; p.color = col(R.color.text_value)
            c.drawText(ellipsize(text, box.width() - 32 * d), box.left + 16 * d, box.centerY() + 9 * d, p)
            y += rowH
        }
    }

    /**
     * Download mark: a thin bar along the bottom of the card, filled as far as the preparation went, with its count;
     * a short bar and an arrow while tiles around the car are downloaded.
     */
    private fun drawDownload(c: Canvas, w: Float, h: Float) {
        val pr = prep
        if (pr == null && live == 0) return
        val y = h - 7 * d
        val x0 = 22 * d; val x1 = w - 22 * d
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = 5 * d
        p.color = (col(R.color.text_sub) and 0x00FFFFFF) or 0x40000000
        c.drawLine(x0, y, x1, y, p)
        p.color = col(R.color.text_value)
        val f = if (pr != null && pr.total > 0) pr.done.toFloat() / pr.total else 0.15f
        c.drawLine(x0, y, x0 + (x1 - x0) * f.coerceIn(0.02f, 1f), y, p)
        p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
        p.typeface = medium; p.textSize = 18 * d; p.textAlign = Paint.Align.RIGHT; p.color = col(R.color.text_sub)
        // Saturated servers: the seconds before the next try, counted down every second
        val wait = pr?.retryLeftS(System.currentTimeMillis())
        val txt = if (pr == null) "\u2193" else "\u2193 ${pr.done} / ${pr.total}" + (wait?.let { " \u00b7 " + waitText(it) } ?: "")
        c.drawText(txt, x1, y - 8 * d, p)
        if (wait != null) postInvalidateDelayed(1_000)
    }

    private fun waitText(s: Long) = if (s >= 60) context.getString(R.string.rp_rep_min, ((s + 30) / 60).toInt()) else context.getString(R.string.rp_rep_s, s.toInt())

    private fun zoneTitle(kind: CamKind, limit: Int?) = kindName(context, kind) + (limit?.let { " · " + limitText(it) } ?: "")

    private fun limitText(kmh: Int) = "${Speeds.toUnit(kmh.toFloat(), miles)} ${Units.speedUnit(context)}"

    private fun statusLine(): String = when (s.status) {
        RadarRepo.Status.NO_POSITION -> context.getString(R.string.rad_no_position)
        RadarRepo.Status.LOADING -> context.getString(R.string.rad_loading)
        RadarRepo.Status.NO_DATA -> context.getString(R.string.rad_unknown)
        RadarRepo.Status.WIFI_ONLY -> context.getString(R.string.rad_wifi_only)
        RadarRepo.Status.OFF_ROAD -> context.getString(R.string.rad_off_road)
        RadarRepo.Status.OK -> s.zone?.let { zoneTitle(it.kind, it.limit) + " · " + context.getString(R.string.rad_zone_end, distText(context, it.toEnd)) }
            ?: s.next.firstOrNull()?.let { context.getString(R.string.rad_next_zone, kindName(context, it.kind), distText(context, it.dist)) }
            ?: context.getString(R.string.rad_no_zone)
    }

    private fun ellipsize(t: String, width: Float): String {
        if (p.measureText(t) <= width) return t
        var e = t
        while (e.length > 1 && p.measureText("$e…") > width) e = e.dropLast(1)
        return "$e…"
    }

    companion object {
        fun kindName(c: Context, k: CamKind?): String = c.getString(when (k) {
            CamKind.FIXED -> R.string.rad_kind_fixed
            CamKind.RED_LIGHT -> R.string.rad_kind_red
            CamKind.SECTION -> R.string.rad_kind_section
            CamKind.DISCRIMINANT -> R.string.rad_kind_discriminant
            else -> R.string.rad_kind_other
        })

        /** 350 m, 1,2 km; 0.4 mi. Rounded to 50 m (or 0.1 mi): the camera's place is not given away. */
        fun distText(c: Context, m: Double): String {
            val loc = Locale.getDefault()
            return if (Units.miles(c)) String.format(loc, "%.1f mi", m / 1609.344)
            else if (m < 1000) String.format(loc, "%d m", (Math.round(m / 50.0) * 50).toInt().coerceAtLeast(50))
            else String.format(loc, "%.1f km", m / 1000.0)
        }
    }
}
