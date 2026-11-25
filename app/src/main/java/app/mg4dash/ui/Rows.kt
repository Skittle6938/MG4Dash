package app.mg4dash.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.mg4dash.R

/**
 * The building blocks shared by every settings window and the Settings page (design "grouped rows", 24/11): a title
 * bar with the round close button, groups of rows with thin dividers where the whole row is the touch target, a
 * switch for yes / no, the chosen value in the theme's colour for a choice. Only the theme's colours.
 */
object Rows {
    const val ROW_DP = 88
    /** The one set of sizes (24/11): a row's title, its description, a value, a button, a section title; controls 56 dp high. */
    const val TITLE_SP = 20f
    const val SUB_SP = 15f
    const val VALUE_SP = 20f
    const val BUTTON_SP = 19f
    const val HEADER_SP = 14f
    const val CONTROL_DP = 56

    /** A text's size in the kit: 20 sp for a title or a value, 15 sp for a description, the large figures as they are. */
    fun textSp(sp: Float) = when { sp <= 16f -> SUB_SP; sp < 21f -> TITLE_SP; else -> sp }
    const val TAG_ROW = "mg4dash.row"
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()
    private fun col(c: Context, res: Int) = Themes.color(c, res)

    /** A group's background: a rounded block a step above the window. */
    fun groupBackground(c: Context): GradientDrawable = GradientDrawable().apply {
        setColor(androidx.core.graphics.ColorUtils.blendARGB(col(c, R.color.button_idle), col(c, R.color.panel) or 0xFF000000.toInt(), 0.3f))
        cornerRadius = dp(c, 22).toFloat()
    }

    /** A vertical group of rows (dividers are added by [add]). */
    fun group(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; background = groupBackground(c); setPadding(dp(c, 24), 0, dp(c, 24), 0)
    }

    /** Adds [row] to [parent], with a thin divider when the previous child is a row too. */
    fun add(parent: LinearLayout, row: View, lp: LinearLayout.LayoutParams = LinearLayout.LayoutParams(-1, -2)) {
        val last = if (parent.childCount > 0) parent.getChildAt(parent.childCount - 1) else null
        if (last?.tag == TAG_ROW && row.tag == TAG_ROW) parent.addView(divider(parent.context), LinearLayout.LayoutParams(-1, maxOf(1, dp(parent.context, 1) / 2 + 0)))
        parent.addView(row, lp)
    }

    fun divider(c: Context) = View(c).apply { setBackgroundColor(col(c, R.color.panel_border)) }

    /** Pressed feedback on a whole row (no colour when idle). */
    fun touchable(v: View) {
        val c = v.context
        val mask = GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()); cornerRadius = dp(c, 12).toFloat() }
        v.background = RippleDrawable(android.content.res.ColorStateList.valueOf(col(c, R.color.button_pressed)), null, mask)
        v.isClickable = true; v.isFocusable = true
    }

    /**
     * A row: [title] (and [sub] in grey) on the left, [control] on the right; touching anywhere on it runs [onClick].
     * At least [ROW_DP] high.
     */
    fun row(c: Context, title: CharSequence, sub: CharSequence?, control: View?, onClick: (() -> Unit)?): LinearLayout {
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; tag = TAG_ROW
            minimumHeight = dp(c, ROW_DP); setPadding(0, dp(c, 12), 0, dp(c, 12))
        }
        val texts = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(c).apply { text = title; setTextColor(col(c, R.color.text_value)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f) })
        if (!sub.isNullOrEmpty()) texts.addView(TextView(c).apply { text = sub; setTextColor(col(c, R.color.text_sub)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f) },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(c, 3) })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        control?.let { row.addView(it, (it.layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(-2, -2)).apply { leftMargin = dp(c, 24) }) }
        if (onClick != null) { touchable(row); row.setOnClickListener { onClick() } }
        return row
    }

    /** The chosen value of a choice, in the theme's colour, with ▾. */
    fun value(c: Context, label: String) = TextView(c).apply {
        text = "$label  ▾"; setTextColor(Themes.select(c)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); typeface = medium
        gravity = Gravity.CENTER_VERTICAL or Gravity.END; maxWidth = dp(c, 420)
    }

    /** "›" at the end of a row that opens something. */
    fun chevron(c: Context) = TextView(c).apply { text = "›"; setTextColor(col(c, R.color.text_title)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f) }

    /** A small grey section title above a group. */
    fun header(c: Context, title: String) = TextView(c).apply {
        text = title.uppercase(); setTextColor(col(c, R.color.text_title)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); typeface = medium; letterSpacing = 0.14f
        setPadding(dp(c, 4), dp(c, 14), 0, dp(c, 8))
    }

    /** An outlined button in the theme's colour (or red for what erases) doing one thing. */
    fun actionButton(c: Context, label: String, danger: Boolean = false, onClick: () -> Unit) = TextView(c).apply {
        text = label; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f); typeface = medium
        val tint = if (danger) col(c, R.color.accent_danger).let { if (Themes.isLight(c)) 0xFFC62828.toInt() else it } else Themes.select(c)
        setTextColor(tint)
        background = outlined(c, tint)
        setPadding(dp(c, 24), 0, dp(c, 24), 0); minWidth = dp(c, 160); minHeight = dp(c, 56)
        setOnClickListener { onClick() }
    }

    /** A plain text button (Cancel). */
    fun ghostButton(c: Context, label: String, onClick: () -> Unit) = TextView(c).apply {
        text = label; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f); typeface = medium; setTextColor(col(c, R.color.text_value))
        setPadding(dp(c, 24), 0, dp(c, 24), 0); minWidth = dp(c, 140); minHeight = dp(c, 56); touchable(this); setOnClickListener { onClick() }
    }

    private fun outlined(c: Context, tint: Int): StateListDrawable {
        fun shape(fill: Int) = GradientDrawable().apply {
            setColor(fill); cornerRadius = c.resources.getDimension(R.dimen.radius_button); setStroke(dp(c, 2), tint)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape((tint and 0x00FFFFFF) or 0x40000000))
            addState(intArrayOf(), shape((tint and 0x00FFFFFF) or 0x24000000))
        }
    }

    /** The round close button (56 dp), top right of every window and of the Settings page. */
    fun closeButton(c: Context, onClick: () -> Unit) = ImageView(c).apply {
        setImageDrawable(CloseIcon(col(c, R.color.text_value), dp(c, 2).toFloat() + dp(c, 1) / 3f))
        val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(col(c, R.color.button_idle)) }
        background = RippleDrawable(android.content.res.ColorStateList.valueOf(col(c, R.color.button_pressed)), bg, null)
        val p = dp(c, 16); setPadding(p, p, p, p)
        contentDescription = c.getString(R.string.st_close)
        isClickable = true; setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(c, 56), dp(c, 56))
    }

    /** A window's title bar: optional icon in the theme's colour, the name, the close button. */
    fun titleBar(c: Context, icon: Int?, title: String, sub: String? = null, onClose: () -> Unit): LinearLayout {
        val bar = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(c, 84) }
        icon?.let {
            bar.addView(ImageView(c).apply { setImageResource(it); setColorFilter(Themes.select(c)) }, LinearLayout.LayoutParams(dp(c, 30), dp(c, 30)).apply { rightMargin = dp(c, 16) })
        }
        val texts = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(c).apply { text = title; setTextColor(col(c, R.color.text_value)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 25f); typeface = medium })
        sub?.let { texts.addView(TextView(c).apply { text = it; setTextColor(col(c, R.color.text_sub)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f) }) }
        bar.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(closeButton(c, onClose), LinearLayout.LayoutParams(dp(c, 56), dp(c, 56)))
        return bar
    }

    /**
     * [content] in a scrolling area no taller than [maxPx] (24/11: the driving window did not fit the screen and did
     * not scroll); left as it is when it scrolls by itself already. Faded edges say there is more.
     */
    fun scrolling(c: Context, content: View, maxPx: Int): Bounded {
        (content.parent as? ViewGroup)?.removeView(content)
        // A content that scrolls by itself still gets the bound (25/11: long release notes pushed Install off screen)
        if (content is android.widget.ScrollView) return Bounded(c, maxPx).apply { addView(content, android.widget.FrameLayout.LayoutParams(-1, -2)) }
        val scroll = android.widget.ScrollView(c).apply {
            isVerticalScrollBarEnabled = true; isScrollbarFadingEnabled = false; scrollBarStyle = View.SCROLLBARS_OUTSIDE_INSET
            isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(dp(c, 36)); overScrollMode = View.OVER_SCROLL_NEVER
            addView(content, android.widget.FrameLayout.LayoutParams(-1, -2))
            // Room under the last row (24/11: windows looked cut at the bottom): its rounded edge in sight once at the end,
            // where the fade is gone
            setPadding(0, 0, 0, dp(c, 20)); clipToPadding = false
        }
        return Bounded(c, maxPx).apply { addView(scroll, android.widget.FrameLayout.LayoutParams(-1, -2)) }
    }

    /** A box no taller than [maxPx], nor than its parent allows; [maxPx] can be lowered once the real room is known. */
    class Bounded(c: Context, var maxPx: Int) : android.widget.FrameLayout(c) {
        override fun onMeasure(w: Int, h: Int) {
            val parent = if (View.MeasureSpec.getMode(h) == View.MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else View.MeasureSpec.getSize(h)
            super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(minOf(maxPx, parent).coerceAtLeast(0), View.MeasureSpec.AT_MOST))
        }
    }

    /** The height left to a window's content on this screen: title bar, margins and [buttons] row taken off. */
    fun contentRoom(c: Context, buttons: Boolean = false): Int {
        // The height the activity really has (24/11: on the car the window was taller than this and was cut)
        val screen = (c as? android.app.Activity)?.window?.decorView?.height?.takeIf { it > 0 } ?: c.resources.displayMetrics.heightPixels
        return screen - dp(c, 84 + 28 + 64) - (if (buttons) dp(c, 80) else 0)
    }

    /** A window's background: the panel colour made opaque, large corners, thin outline. */
    fun windowBackground(c: Context) = GradientDrawable().apply {
        setColor(col(c, R.color.panel) or 0xFF000000.toInt()); cornerRadius = dp(c, 28).toFloat()
        setStroke(maxOf(1, dp(c, 1)), col(c, R.color.panel_border))
    }

    /** A cross, drawn (no icon file): same stroke as the app's line icons. */
    private class CloseIcon(private val color: Int, private val width: Float) : android.graphics.drawable.Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        override fun draw(canvas: Canvas) {
            val b = bounds; val i = b.width() * 0.18f
            p.color = color; p.strokeWidth = width
            canvas.drawLine(b.left + i, b.top + i, b.right - i, b.bottom - i, p)
            canvas.drawLine(b.right - i, b.top + i, b.left + i, b.bottom - i, p)
        }
        override fun setAlpha(alpha: Int) { p.alpha = alpha }
        override fun setColorFilter(cf: android.graphics.ColorFilter?) { p.colorFilter = cf }
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }
}

/**
 * Yes / no as a switch: a track in the theme's colour when on, a round thumb. Its row toggles it (it does not take
 * touches itself, so a touch anywhere on the row works the same).
 */
class SwitchView(c: Context) : View(c) {
    var on = false; set(v) { field = v; contentDescription = context.getString(if (v) R.string.yes else R.string.no); invalidate() }
    private val d = c.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    init { layoutParams = LinearLayout.LayoutParams((84 * d).toInt(), (48 * d).toInt()); isClickable = false; isFocusable = false; on = false }
    override fun onDraw(canvas: Canvas) {
        val sel = Themes.select(context)
        val h = height.toFloat(); val w = width.toFloat(); val stroke = 2 * d
        r.set(stroke / 2, stroke / 2, w - stroke / 2, h - stroke / 2)
        p.style = Paint.Style.FILL; p.color = if (on) sel else Themes.color(context, R.color.button_idle)
        canvas.drawRoundRect(r, h / 2, h / 2, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = stroke; p.color = if (on) sel else Themes.color(context, R.color.panel_border)
        canvas.drawRoundRect(r, h / 2, h / 2, p)
        val radius = h / 2 - 7 * d
        val cx = if (on) w - h / 2 else h / 2
        p.style = Paint.Style.FILL
        p.color = if (on) (Themes.color(context, R.color.panel) or 0xFF000000.toInt()) else Themes.color(context, R.color.text_title)
        canvas.drawCircle(cx, h / 2, radius, p)
    }
}

/** A box to tick: rounded square, filled with the theme's colour and a tick when on. */
class CheckView(c: Context) : View(c) {
    var on = false; set(v) { field = v; invalidate() }
    private val d = c.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val tick = android.graphics.Path()
    init { layoutParams = LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()); isClickable = false; isFocusable = false }
    override fun onDraw(canvas: Canvas) {
        val sel = Themes.select(context); val s = width.toFloat(); val stroke = 2 * d
        r.set(stroke / 2, stroke / 2, s - stroke / 2, s - stroke / 2)
        p.style = Paint.Style.FILL; p.color = if (on) sel else Themes.color(context, R.color.button_idle)
        canvas.drawRoundRect(r, 12 * d, 12 * d, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = stroke; p.color = if (on) sel else Themes.color(context, R.color.panel_border)
        canvas.drawRoundRect(r, 12 * d, 12 * d, p)
        if (on) {
            tick.reset(); tick.moveTo(s * 0.25f, s * 0.52f); tick.lineTo(s * 0.43f, s * 0.70f); tick.lineTo(s * 0.76f, s * 0.32f)
            p.strokeWidth = 3.2f * d; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
            p.color = Themes.color(context, R.color.panel) or 0xFF000000.toInt()
            canvas.drawPath(tick, p)
        }
    }
}
