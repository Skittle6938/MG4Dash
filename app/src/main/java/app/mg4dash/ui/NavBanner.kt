package app.mg4dash.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.mg4dash.R
import app.mg4dash.map.NavRepo
import java.util.Locale

/**
 * Guidance banner placed on the home map during a DiAuto trip:
 * next manoeuvre arrow, distance, street, then time remaining and arrival time.
 */
class NavBanner(context: Context) : LinearLayout(context) {
    private val d = resources.displayMetrics.density
    private val arrow = NavArrowView(context)
    private val distance = TextView(context)
    private val road = TextView(context)
    private val eta = TextView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // Opaque background: the map and its title must not show through under the guidance
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 16 * d
            setColor(Themes.color(context, R.color.bg_bottom))
            setStroke((2 * d).toInt(), Themes.color(context, R.color.accent_action))
        }
        elevation = 8 * d
        setPadding((14 * d).toInt(), (10 * d).toInt(), (20 * d).toInt(), (10 * d).toInt())
        addView(arrow, LayoutParams((88 * d).toInt(), (88 * d).toInt()))
        val col = LinearLayout(context).apply { orientation = VERTICAL }
        distance.apply { setTextColor(Themes.color(context, R.color.text_value)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f); maxLines = 1 }
        road.apply {
            setTextColor(Themes.color(context, R.color.text_value)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        eta.apply { setTextColor(Themes.color(context, R.color.text_sub)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); maxLines = 1 }
        col.addView(distance); col.addView(road); col.addView(eta)
        addView(col, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = (14 * d).toInt() })
        visibility = GONE
    }

    fun show(n: NavRepo.Nav?) {
        if (n == null) { visibility = GONE; return }
        visibility = VISIBLE
        arrow.set(n)
        distance.text = n.distanceMeters?.takeIf { it > 0 }?.let { formatDistance(it) } ?: ""
        distance.visibility = if (distance.text.isEmpty()) GONE else VISIBLE
        road.text = n.road.ifBlank { n.actionText }
        road.visibility = if (road.text.isEmpty()) GONE else VISIBLE
        val left = listOfNotNull(
            n.totalSeconds?.takeIf { it > 0 }?.let { s -> if (s >= 3600) "${s / 3600} h ${(s % 3600) / 60} min" else "${(s + 59) / 60} min" },
            n.totalMeters?.takeIf { it > 0 }?.let { formatDistance(it) },
            n.arrival.takeIf { it.isNotBlank() }?.let { "→ $it" },
        )
        eta.text = left.joinToString("  ·  ")
        eta.visibility = if (left.isEmpty()) GONE else VISIBLE
    }

    private fun formatDistance(m: Int): String = if (app.mg4dash.system.Units.miles(context)) {
        // Miles above 0.1 mi, otherwise yards (British usage)
        val mi = m / 1609.344f
        when {
            mi >= 10f -> "${Math.round(mi)} mi"
            mi >= 0.1f -> String.format(Locale.getDefault(), "%.1f mi", mi)
            else -> "${(Math.round(m * 1.0936f) / 10) * 10} yd"
        }
    } else when {
        m >= 10_000 -> "${Math.round(m / 1000f)} km"
        m >= 1000 -> String.format(Locale.getDefault(), "%.1f km", m / 1000f)
        m >= 100 -> "${(m / 50) * 50} m"
        else -> "${(m / 10) * 10} m"
    }
}

/** Drawn manoeuvre arrow (straight, turn, U-turn, roundabout with exit, arrival). */
class NavArrowView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 9 * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        color = Themes.color(context, R.color.text_value)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Themes.color(context, R.color.text_value) }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Themes.color(context, R.color.accent_action) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Themes.color(context, R.color.text_value); textAlign = Paint.Align.CENTER
        textSize = 22 * d; typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private var nav: NavRepo.Nav? = null

    fun set(n: NavRepo.Nav) { nav = n; invalidate() }

    override fun onDraw(c: Canvas) {
        val n = nav ?: return
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2; val sign = when (n.side) { 1 -> -1f; 2 -> 1f; else -> 0f }
        when (n.eventType) {
            NavRepo.Turn.DESTINATION -> {   // arrival marker
                c.drawCircle(cx, h * 0.38f, w * 0.24f, accent)
                c.drawCircle(cx, h * 0.38f, w * 0.09f, fill)
                val p = Path().apply { moveTo(cx - w * 0.17f, h * 0.55f); lineTo(cx, h * 0.9f); lineTo(cx + w * 0.17f, h * 0.55f); close() }
                c.drawPath(p, accent)
            }
            NavRepo.Turn.ROUNDABOUT, NavRepo.Turn.ROUNDABOUT_ENTER, NavRepo.Turn.ROUNDABOUT_EXIT -> {
                val r = w * 0.2f; val cy = h * 0.5f
                c.drawCircle(cx, cy, r, stroke)
                c.drawLine(cx, h * 0.95f, cx, cy + r, stroke)
                // Exit: angle provided (0 = straight), otherwise according to the side
                val deg = n.angle?.toFloat() ?: (sign * 90f)
                val a = Math.toRadians((deg - 90).toDouble())
                val ex = cx + (r * Math.cos(a)).toFloat(); val ey = cy + (r * Math.sin(a)).toFloat()
                val tx = cx + (w * 0.44f * Math.cos(a)).toFloat(); val ty = cy + (h * 0.44f * Math.sin(a)).toFloat()
                c.drawLine(ex, ey, tx, ty, stroke)
                head(c, tx, ty, a)
                n.exitNumber?.takeIf { it > 0 }?.let { c.drawText("$it", cx, cy + text.textSize * 0.35f, text) }
            }
            NavRepo.Turn.U_TURN -> {
                val s = if (sign == 0f) -1f else sign
                val x1 = cx - s * w * 0.12f; val x2 = cx + s * w * 0.18f
                val p = Path().apply {
                    moveTo(x1, h * 0.92f); lineTo(x1, h * 0.38f)
                    arcTo(RectF(minOf(x1, x2), h * 0.12f, maxOf(x1, x2), h * 0.12f + Math.abs(x2 - x1)), 180f, 180f * s, false)
                    lineTo(x2, h * 0.55f)
                }
                c.drawPath(p, stroke)
                head(c, x2, h * 0.6f, Math.PI / 2)
            }
            else -> {
                val deg = when (n.eventType) {
                    NavRepo.Turn.TURN -> 90f
                    NavRepo.Turn.SHARP -> 135f
                    NavRepo.Turn.SLIGHT, NavRepo.Turn.FORK, NavRepo.Turn.MERGE, NavRepo.Turn.ON_RAMP, NavRepo.Turn.OFF_RAMP -> 45f
                    else -> 0f
                } * sign
                val jy = h * 0.55f
                c.drawLine(cx, h * 0.95f, cx, jy, stroke)
                val a = Math.toRadians((deg - 90).toDouble())
                val len = h * 0.42f
                val tx = cx + (len * Math.cos(a)).toFloat(); val ty = jy + (len * Math.sin(a)).toFloat()
                c.drawLine(cx, jy, tx, ty, stroke)
                head(c, tx, ty, a)
            }
        }
    }

    /** Arrow head at point (x, y), oriented by angle a (radians). */
    private fun head(c: Canvas, x: Float, y: Float, a: Double) {
        val s = 16 * d
        val p = Path().apply {
            moveTo(x + (s * Math.cos(a)).toFloat(), y + (s * Math.sin(a)).toFloat())
            lineTo(x + (s * Math.cos(a + 2.4)).toFloat(), y + (s * Math.sin(a + 2.4)).toFloat())
            lineTo(x + (s * Math.cos(a - 2.4)).toFloat(), y + (s * Math.sin(a - 2.4)).toFloat())
            close()
        }
        c.drawPath(p, fill)
    }
}
