package app.mg4dash.ui

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.mg4dash.R

/**
 * A number set exactly (24/11, the alerter's thresholds and times, once lists): − and + (56 dp), held they repeat
 * faster and faster; a touch on the value types it with MG4Dash's keyboard. [min] to [max], [step] by default; the
 * value written by [label] (its unit with it). [parse] reads what is typed (by default a whole number).
 */
class Stepper(
    private val a: Activity,
    val min: Int, val max: Int,
    private val step: (Int, Int) -> Int = { _, _ -> 1 },
    private val label: (Int) -> String,
    private val typedTitle: String,
    private val parse: (String) -> Int? = { it.trim().replace(',', '.').toFloatOrNull()?.toInt() },
    private val get: () -> Int, private val set: (Int) -> Unit,
) {
    private val d = a.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val value = TextView(a).apply {
        gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, Rows.VALUE_SP); setTextColor(Themes.select(a))
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        minWidth = dp(120); setPadding(dp(8), 0, dp(8), 0); Rows.touchable(this)
        setOnClickListener { type() }
    }
    private val minus = button("−", -1)
    private val plus = button("+", 1)

    /** − value +, for the end of a row. */
    val view: LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(minus, LinearLayout.LayoutParams(dp(Rows.CONTROL_DP), dp(Rows.CONTROL_DP)))
        addView(value, LinearLayout.LayoutParams(-2, dp(Rows.CONTROL_DP)).apply { leftMargin = dp(6); rightMargin = dp(6) })
        addView(plus, LinearLayout.LayoutParams(dp(Rows.CONTROL_DP), dp(Rows.CONTROL_DP)))
    }

    init { paint() }

    /** The value [v] kept within [min] … [max]. */
    fun clamp(v: Int) = v.coerceIn(min, max)

    fun paint() {
        val v = get()
        value.text = label(v)
        minus.alpha = if (v <= min) 0.35f else 1f; plus.alpha = if (v >= max) 0.35f else 1f
    }

    /** One step down ([dir] −1) or up (+1). */
    fun nudge(dir: Int) {
        val v = get()
        val n = clamp(v + dir * step(v, dir))
        if (n != v) { set(n); paint() }
    }

    /** Typed with MG4Dash's keyboard, the unit and the limits in the title. */
    fun type() = Ask.show(a, "$typedTitle (${label(min)} – ${label(max)})", "", secret = false, number = true) { t ->
        parse(t)?.let { set(clamp(it)); paint() }
    }

    private fun button(glyph: String, dir: Int) = TextView(a).apply {
        text = glyph; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f); setTextColor(Themes.color(a, R.color.text_value))
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Themes.color(a, R.color.button_idle)); setStroke(dp(1), Themes.color(a, R.color.panel_border)) }
        contentDescription = glyph
        // Held: repeats, faster and faster
        val h = Handler(Looper.getMainLooper())
        var delay = 400L
        val repeat = object : Runnable { override fun run() { nudge(dir); delay = (delay * 0.75).toLong().coerceAtLeast(60L); h.postDelayed(this, delay) } }
        setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { v.isPressed = true; nudge(dir); delay = 400L; h.postDelayed(repeat, 500L) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; h.removeCallbacks(repeat) }
            }
            true
        }
    }

    companion object {
        /** Seconds written in the unit that suits them: "30 s", "2 min", "1 h 30". */
        fun duration(sec: Int): String = when {
            sec < 60 -> "$sec s"
            sec < 3600 -> if (sec % 60 == 0) "${sec / 60} min" else "${sec / 60} min ${sec % 60}"
            else -> if (sec % 3600 == 0) "${sec / 3600} h" else "${sec / 3600} h ${(sec % 3600) / 60}"
        }

        /** The step for seconds, by their size: 5 s, then 30 s, then 1 min, then 10 min. */
        fun durationStep(sec: Int, dir: Int): Int {
            val v = if (dir < 0) sec - 1 else sec
            return when { v < 60 -> 5; v < 600 -> 30; v < 3600 -> 60; else -> 600 }
        }

        /** Hours written as hours or days: "6 h", "2 j". */
        fun hours(h: Int, day: String): String = if (h < 24 || h % 24 != 0) "$h h" else "${h / 24} $day"

        /** The step for hours: 1 h below a day, then a day. */
        fun hoursStep(h: Int, dir: Int): Int = if ((if (dir < 0) h - 1 else h) < 24) 1 else 24

        /** A duration typed: "90" seconds, "2 min", "1h30", "1:30". */
        fun parseDuration(t: String): Int? {
            val s = t.trim().lowercase().replace(" ", "")
            Regex("^(\\d+):(\\d{1,2})$").find(s)?.let { return it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
            Regex("^(\\d+)h(\\d+)?$").find(s)?.let { return it.groupValues[1].toInt() * 3600 + (it.groupValues[2].toIntOrNull() ?: 0) * 60 }
            Regex("^(\\d+)min$").find(s)?.let { return it.groupValues[1].toInt() * 60 }
            Regex("^(\\d+)s?$").find(s)?.let { return it.groupValues[1].toInt() }
            return null
        }
    }
}
