package app.mg4dash.ui

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.mg4dash.R

/**
 * The eight layouts (#38): their drawing, and the menu that chooses one with its drawings (Settings › Home and the
 * edit mode's Layout button), in the theme's colours.
 */
object LayoutPicker {
    /** Drawing of [layout]: its places outlined, the big one (map, square, band) filled. */
    class Glyph(private val layout: HomeSlots.Layout, private val line: Int, private val fill: Int, private val d: Float,
                private val w: Int = 46, private val h: Int = 28) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.5f * d }
        private val r = RectF()

        override fun draw(c: Canvas) {
            val b = bounds
            val cells = layout.cells(2.5f * d / b.width(), 2.5f * d / b.height())
            layout.slots.forEach { s ->
                val f = cells[s.id] ?: return@forEach
                val inset = p.strokeWidth / 2
                r.set(b.left + f.left * b.width() + inset, b.top + f.top * b.height() + inset, b.left + f.right * b.width() - inset, b.top + f.bottom * b.height() - inset)
                if (s.size != HomeSlots.Size.SMALL && s.size != HomeSlots.Size.MEDIUM) {
                    p.style = Paint.Style.FILL; p.color = fill; c.drawRoundRect(r, 2 * d, 2 * d, p)
                }
                p.style = Paint.Style.STROKE; p.color = line; c.drawRoundRect(r, 2 * d, 2 * d, p)
            }
        }

        override fun getIntrinsicWidth() = (w * d).toInt()
        override fun getIntrinsicHeight() = (h * d).toInt()
        override fun setAlpha(a: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Suppress("OVERRIDE_DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    fun glyph(context: Context, layout: HomeSlots.Layout, w: Int = 46, h: Int = 28): Glyph =
        Glyph(layout, Themes.color(context, R.color.text_value), Themes.color(context, R.color.text_disabled), context.resources.displayMetrics.density, w, h)

    /**
     * The menu: two columns of drawings and names, [current] ticked. Opened under [anchor], or beside it ([side]:
     * a button of the vertical bar), never off the screen.
     */
    fun show(activity: Activity, anchor: View, current: HomeSlots.Layout?, side: Boolean = false, onPick: (HomeSlots.Layout) -> Unit) {
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            // Opaque, in the theme's neutral colours: the edit mode's boxes do not show through
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = activity.resources.getDimension(R.dimen.radius_panel)
                setColor(Themes.color(activity, R.color.bg_top)); setStroke(dp(2), Themes.color(activity, R.color.panel_border))
            }
            setPadding(dp(10), dp(12), dp(10), dp(14))
            elevation = 12 * d
        }
        val popup = android.widget.PopupWindow(box, dp(880), LinearLayout.LayoutParams.WRAP_CONTENT, false).apply {
            isOutsideTouchable = true
            setOnDismissListener { Fullscreen.apply(activity) }
        }
        box.addView(TextView(activity).apply {
            text = activity.getString(R.string.ly_title).uppercase(); letterSpacing = 0.12f
            setTextColor(Themes.color(activity, R.color.text_title)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(14), 0, 0, dp(6))
        })
        val all = HomeSlots.Layout.entries
        val half = (all.size + 1) / 2
        for (row in 0 until half) {
            val line = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            listOf(all[row], all.getOrNull(row + half)).forEachIndexed { i, layout ->
                val cell = TextView(activity).apply {
                    if (layout == null) return@apply
                    text = activity.getString(layout.label)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f); maxLines = 1
                    setTextColor(Themes.color(activity, R.color.text_value))
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), 0, dp(14), 0)
                    setCompoundDrawablesRelativeWithIntrinsicBounds(glyph(activity, layout, 60, 36), null, if (layout == current) tick(activity) else null, null)
                    compoundDrawablePadding = dp(18)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat()
                        setColor(Themes.color(activity, if (layout == current) R.color.button_pressed else R.color.button_idle))
                        if (layout == current) setStroke(dp(2), Themes.color(activity, R.color.text_value))
                    }
                    setOnClickListener { popup.dismiss(); onPick(layout) }
                }
                line.addView(cell, LinearLayout.LayoutParams(0, dp(64), 1f).apply { if (i > 0) leftMargin = dp(10) })
            }
            box.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        box.measure(View.MeasureSpec.makeMeasureSpec(popup.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        popup.height = box.measuredHeight + dp(6)
        val at = IntArray(2).also { anchor.getLocationOnScreen(it) }
        val screenW = anchor.rootView.width; val screenH = anchor.rootView.height
        val x: Int; val y: Int
        if (side) {
            // Beside a button of the vertical bar: towards the screen's middle
            x = if (at[0] < screenW / 2) at[0] + anchor.width + dp(8) else at[0] - popup.width - dp(8)
            y = at[1] + anchor.height / 2 - popup.height / 2
        } else {
            x = at[0] + anchor.width / 2 - popup.width / 2
            // Under the button, or above it when there is no room below (the bottom bar)
            y = if (at[1] + anchor.height + popup.height + dp(4) <= screenH) at[1] + anchor.height + dp(4) else at[1] - popup.height - dp(4)
        }
        popup.showAtLocation(anchor.rootView, Gravity.TOP or Gravity.START,
            x.coerceIn(0, (screenW - popup.width).coerceAtLeast(0)), y.coerceIn(0, (screenH - popup.height).coerceAtLeast(0)))
        Themes.track(popup)
    }

    /** ✓ at the end of the chosen layout. */
    private fun tick(context: Context): Drawable = object : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Themes.color(context, R.color.text_value); textSize = 24 * context.resources.displayMetrics.scaledDensity; textAlign = Paint.Align.CENTER
        }
        override fun draw(c: Canvas) { c.drawText("✓", bounds.exactCenterX(), bounds.exactCenterY() - (p.descent() + p.ascent()) / 2, p) }
        override fun getIntrinsicWidth() = (28 * context.resources.displayMetrics.density).toInt()
        override fun getIntrinsicHeight() = (28 * context.resources.displayMetrics.density).toInt()
        override fun setAlpha(a: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Suppress("OVERRIDE_DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
