package app.mg4dash.showroom

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.mg4dash.R
import app.mg4dash.ui.Kit
import app.mg4dash.ui.Themes

/** The redesigned pages' small parts (26/11): cards, state tiles, switches, as in the v4 mock-up. */
class ShowroomKit(private val c: Context, val kit: Kit) {
    fun dp(v: Int) = kit.dp(v)
    fun color(res: Int) = Themes.color(c, res)

    /** A raised card with rounded corners. */
    /** A frame's background a little less see-through (Marc, 27/11): its alpha raised to [alpha] at least. */
    private fun opaque(c: Int, alpha: Int): Int = if ((c ushr 24) >= alpha) c else (c and 0xFFFFFF) or (alpha shl 24)

    fun card(padH: Int = 18, padV: Int = 16): LinearLayout = kit.column().apply {
        background = GradientDrawable().apply { setColor(opaque(color(R.color.panel), 0xF2)); cornerRadius = dp(18).toFloat(); setStroke(1, color(R.color.panel_border)) }
        setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
    }

    /** The small upper-case title of a card. */
    fun label(text: String, sp: Float = 15f): TextView = kit.text(text.uppercase(), sp, R.color.text_title).apply {
        letterSpacing = .1f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** A state tile: label, value, and a line under it; [dot] lights a small green dot (null: none). */
    inner class Tile(compact: Boolean = false) {
        val view: LinearLayout = kit.column().apply {
            background = GradientDrawable().apply { setColor(opaque(color(R.color.panel_raised), 0xF7)); cornerRadius = dp(if (compact) 14 else 16).toFloat() }
            setPadding(dp(14), dp(if (compact) 8 else 12), dp(14), dp(if (compact) 8 else 12))
        }
        private val head = kit.row()
        private val dotView = View(c).apply { visibility = View.GONE }
        val title: TextView = kit.text("", if (compact) 13f else 14f, R.color.text_title)
        val value: TextView = kit.text("—", if (compact) 20f else 22f).apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END }
        val sub: TextView = kit.text("", 13f, R.color.text_sub).apply { visibility = View.GONE }
        init {
            head.addView(dotView, kit.lp(dp(9), dp(9), r = 7))
            head.addView(title, kit.lp(0, -2, 1f))
            view.addView(head, kit.lp(-1, -2))
            view.addView(value, kit.lp(-1, -2, t = 5))
            view.addView(sub, kit.lp(-1, -2, t = 3))
        }
        fun dot(on: Boolean?) {
            dotView.visibility = if (on == null) View.GONE else View.VISIBLE
            dotView.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color(if (on == true) R.color.accent_energy else R.color.gauge_off)) }
        }
        fun enabled(on: Boolean) { view.alpha = if (on) 1f else .55f; view.isClickable = on }
        fun subText(t: String?) { sub.text = t.orEmpty(); sub.visibility = if (t.isNullOrEmpty()) View.GONE else View.VISIBLE }
    }

    /** A segmented choice; returns the row and how to show the chosen one. */
    fun segments(labels: List<String>, sp: Float = 17f, onPick: (Int) -> Unit): Pair<LinearLayout, (Int?) -> Unit> {
        val row = kit.row().apply {
            background = GradientDrawable().apply { setColor(color(R.color.panel_raised)); cornerRadius = dp(14).toFloat() }
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val cells = labels.mapIndexed { i, l ->
            TextView(c).apply {
                text = l; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); maxLines = 1
                setOnClickListener { onPick(i) }
            }.also { row.addView(it, kit.lp(0, dp(46), 1f)) }
        }
        return row to { sel ->
            cells.forEachIndexed { i, v ->
                val on = i == sel
                v.setTextColor(color(if (on) R.color.on_action else R.color.text_sub))
                v.background = if (on) GradientDrawable().apply { setColor(color(R.color.accent_action)); cornerRadius = dp(11).toFloat() } else null
                v.typeface = Typeface.create("sans-serif-medium", if (on) Typeface.BOLD else Typeface.NORMAL)
            }
        }
    }

    /** "Label …… [switch]" line. */
    fun switchRow(label: String, onToggle: () -> Unit): Pair<LinearLayout, MiniSwitch> {
        val r = kit.row().apply { minimumHeight = dp(40); setOnClickListener { onToggle() } }
        r.addView(kit.text(label, 18f, R.color.text_sub), kit.lp(0, -2, 1f))
        val s = MiniSwitch(c, color(R.color.accent_energy), color(R.color.gauge_off))
        r.addView(s, kit.lp(dp(46), dp(26)))
        return r to s
    }
}

/** A small on / off switch, drawn. */
class MiniSwitch(context: Context, private val onColor: Int, private val offColor: Int) : View(context) {
    var on = false
        set(v) { if (field != v) { field = v; invalidate() } }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat(); val w = width.toFloat()
        p.color = if (on) onColor else offColor
        canvas.drawRoundRect(RectF(0f, 0f, w, h), h / 2, h / 2, p)
        p.color = 0xFFFFFFFF.toInt()
        val r = h / 2 - h * .14f
        canvas.drawCircle(if (on) w - h / 2 else h / 2, h / 2, r, p)
    }
}
