package app.mg4dash.cards

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import app.mg4dash.R
import app.mg4dash.media.LyricsChoices
import app.mg4dash.ui.Themes

/**
 * The ▲▼ arrows beside the lyrics (26/11), the shift between them: ▲ moves the lyrics back half a second (the lines come
 * later, the count goes down), ▼ moves them on; a touch on the count puts it back to 0. [big]: the karaoke's (84 dp,
 * "saved for this track" under them), else the card's (52 dp). [shift] is told the new shift, in ms.
 */
internal class LyricsShift(c: Context, private val big: Boolean, private val shift: (Long) -> Unit) : LinearLayout(c) {
    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private var ms = 0L
    private val up = arrow(R.drawable.ic_shift_up, R.string.ly_shift_later) { shift(ms - LyricsChoices.STEP_MS) }
    private val down = arrow(R.drawable.ic_shift_down, R.string.ly_shift_earlier) { shift(ms + LyricsChoices.STEP_MS) }
    private val count = TextView(c).apply {
        gravity = Gravity.CENTER; includeFontPadding = false; typeface = Typeface.DEFAULT_BOLD; maxLines = 1
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (big) 24f else 15f)
        setPadding(dp(if (big) 14 else 6), dp(if (big) 10 else 6), dp(if (big) 14 else 6), dp(if (big) 10 else 6))
        contentDescription = c.getString(R.string.ly_shift_reset)
        setOnClickListener { shift(0) }
    }
    private val saved = TextView(c).apply {
        text = c.getString(R.string.ly_shift_saved); gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(Themes.color(c, R.color.text_sub)); visibility = View.INVISIBLE
    }

    init {
        orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        val size = dp(if (big) 84 else 52)
        val gap = dp(if (big) 14 else 8)
        addView(up, LayoutParams(size, size))
        addView(count, LayoutParams(-2, -2).apply { topMargin = gap; bottomMargin = gap })
        addView(down, LayoutParams(size, size))
        if (big) addView(saved, LayoutParams(dp(150), -2).apply { topMargin = dp(16) })
        paint(0)
    }

    private fun arrow(icon: Int, label: Int, onClick: () -> Unit) = ImageButton(context).apply {
        setImageResource(icon); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        val p = dp(if (big) 20 else 12); setPadding(p, p, p, p)
        imageTintList = android.content.res.ColorStateList.valueOf(Themes.color(context, R.color.text_value))
        background = round(Themes.color(context, R.color.panel_border))
        contentDescription = context.getString(label); setOnClickListener { onClick() }
    }

    private fun round(stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(Themes.color(context, R.color.button_idle))
        setStroke((1.5f * d).toInt().coerceAtLeast(1), stroke)
    }

    /** Shows [shiftMs]: "+1,0 s" in the theme's colour, grey at 0. */
    fun paint(shiftMs: Long) {
        ms = shiftMs
        count.text = label(shiftMs, big)
        val tint = if (shiftMs != 0L) Themes.select(context) else Themes.color(context, R.color.text_sub)
        count.setTextColor(tint)
        count.background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat(); setColor(0); setStroke((1.5f * d).toInt().coerceAtLeast(1), tint)
        }
        saved.visibility = if (shiftMs != 0L) View.VISIBLE else View.INVISIBLE
    }

    companion object {
        /** "+1,0 s" ([unit]) or "+1,0", in the head unit's decimal style; "0,0" at 0. */
        fun label(ms: Long, unit: Boolean): String {
            val v = String.format(java.util.Locale.getDefault(), "%.1f", ms / 1000f)
            return (if (ms > 0) "+" else "") + v + if (unit) " s" else ""
        }
    }
}
