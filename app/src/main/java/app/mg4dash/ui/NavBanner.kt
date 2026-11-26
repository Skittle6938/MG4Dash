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

/** Drawn manoeuvre (straight, turn, U-turn, roundabout with its exit, arrival): see [NavGlyph]. */
class NavArrowView(context: Context) : View(context) {
    private var shape: NavGlyph.Shape? = null

    fun set(n: NavRepo.Nav) { setShape(NavGlyph.shape(n, leftTraffic)) }
    fun setShape(s: NavGlyph.Shape?) { if (s != shape) { shape = s; invalidate() } }

    override fun onDraw(c: Canvas) {
        val s = shape ?: return
        NavGlyph.draw(c, RectF(0f, 0f, width.toFloat(), height.toFloat()), s, Themes.color(context, R.color.text_value))
    }

    /** Traffic on the left where the car is set (UK, Ireland, Malta, Cyprus…): roundabouts go clockwise there. */
    private val leftTraffic: Boolean = java.util.Locale.getDefault().country.uppercase() in LEFT_TRAFFIC

    companion object {
        internal val LEFT_TRAFFIC = setOf("GB", "IE", "MT", "CY", "AU", "NZ", "JP", "IN", "ZA", "HK", "SG", "MY", "TH", "ID")

        /**
         * Where a roundabout's exit lies, in degrees clockwise from straight on (90 = right, 270 = left), as MG4Link
         * draws it on the cluster. Android Auto's [angle] is the degrees travelled round the roundabout from the
         * entrance to the exit, 1 to 360 (four even exits: 90, 180, 270, 360): with the traffic on the right
         * (counter-clockwise) 90 is the exit on the right; clockwise, on the left. The [side] of a roundabout is the
         * side of the traffic (1 left = clockwise, 2 right), otherwise [leftTraffic] for the car's country. Without an
         * angle, the [exitNumber] as even exits (90° each, at most 3); without either, straight on.
         */
        fun roundaboutHeading(side: Int?, exitNumber: Int?, angle: Int?, leftTraffic: Boolean = false, direction: Int = 0): Int {
            val clockwise = clockwise(side, leftTraffic, direction)
            val a = angle?.takeIf { it in 1..360 } ?: exitNumber?.takeIf { it >= 1 }?.let { minOf(it, 3) * 90 } ?: return 0
            return Math.floorMod(if (clockwise) 180 + a else 180 - a, 360)
        }

        /**
         * The roundabout's way round: MG4Link's roundabout_direction when it gives it (1 clockwise, 2 anticlockwise;
         * #74), otherwise the side of the traffic (1 left = clockwise, 2 right), otherwise the car's country.
         */
        fun clockwise(side: Int?, leftTraffic: Boolean, direction: Int = 0): Boolean = when (direction) {
            1 -> true
            2 -> false
            else -> when (side) { 1 -> true; 2 -> false; else -> leftTraffic }
        }
    }
}
