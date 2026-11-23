package app.mg4dash.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.mg4dash.R

/**
 * A widget's own options in a popup, opened from the widget (a long press on the energy card, the gear next to the
 * OpenStreetMap map in its menu): the settings that were in Settings › Home (17/11), in the radar page's style
 * (a title, lines of a title, a grey subtitle and an outlined control). Too tall for the screen, it becomes twice as
 * wide and what follows [column] goes on the right.
 */
class OptionsPopup(private val a: Activity, title: String) {
    private val d = a.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private fun col(res: Int) = Themes.color(a, res)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(30), dp(24), dp(30), dp(22)) }
    private val first = mutableListOf<Pair<View, LinearLayout.LayoutParams>>()
    private val second = mutableListOf<Pair<View, LinearLayout.LayoutParams>>()
    private var target = first
    private val refreshers = mutableListOf<() -> Unit>()
    /** True once shown in two columns. */
    var twoColumns = false; private set

    init {
        body.addView(TextView(a).apply {
            text = title.uppercase(); setTextColor(col(R.color.text_title)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); typeface = medium; letterSpacing = 0.12f
        })
    }

    private fun chip(label: String, onClick: () -> Unit) = TextView(a).apply {
        text = label; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); typeface = medium
        setBackgroundResource(R.drawable.bg_chip); setTextColor(ContextCompat.getColorStateList(a, R.color.chip_text))
        setPadding(dp(18), 0, dp(18), 0); setOnClickListener { onClick() }
    }

    private fun line(title: String, sub: String?, control: TextView, width: Int) {
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(a).apply { text = title; setTextColor(col(R.color.text_value)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f) })
        sub?.let { texts.addView(TextView(a).apply { text = it; setTextColor(col(R.color.text_sub)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f) },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) }) }
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(18) })
        row.addView(control, LinearLayout.LayoutParams(dp(width), dp(56)))
        target += row to LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) }
    }

    /** What follows goes in the right column when the whole is too tall for the screen in one. */
    fun column(): OptionsPopup { target = second; return this }

    /** OUI / NON. */
    fun toggle(title: String, sub: String?, get: () -> Boolean, set: (Boolean) -> Unit): OptionsPopup {
        lateinit var v: TextView
        fun paint() { v.text = a.getString(if (get()) R.string.yes else R.string.no); v.isSelected = get() }
        v = chip("") { set(!get()); refreshers.forEach { it() } }
        refreshers += ::paint
        paint()
        line(title, sub, v, 130)
        return this
    }

    /** A choice in the same menu as the settings'. */
    fun choice(title: String, sub: String?, labels: List<String>, current: () -> Int, enabled: () -> Boolean = { true }, onPick: (Int) -> Unit): OptionsPopup {
        lateinit var v: TextView
        fun paint() { v.text = labels[current().coerceIn(0, labels.lastIndex)] + "  ▾"; v.isEnabled = enabled(); v.alpha = if (v.isEnabled) 1f else .45f }
        v = chip("") { SourceMenu.choose(a, v, labels, current()) { onPick(it); paint() } }.apply { isSelected = true }
        refreshers += ::paint
        paint()
        line(title, sub, v, 240)
        return this
    }

    fun note(view: android.view.View): OptionsPopup {
        target += view to LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
        return this
    }

    fun action(label: String, onClick: () -> Unit): OptionsPopup {
        target += chip(label, onClick) to LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(12) }
        return this
    }

    /** Shows it; [onClose] once it goes (to apply what changed). */
    fun show(onClose: () -> Unit = {}) {
        val close = chip(a.getString(R.string.st_close)) {}
        fun place(into: LinearLayout, items: List<Pair<View, LinearLayout.LayoutParams>>) =
            items.forEach { (v, lp) -> (v.parent as? ViewGroup)?.removeView(v); into.addView(v, lp) }
        // One column when it fits in the screen's height (title, Fermer and the dialog's margins: 200 dp)
        val probe = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        place(probe, first + second)
        probe.measure(View.MeasureSpec.makeMeasureSpec(dp(840), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val screen = a.resources.displayMetrics
        twoColumns = second.isNotEmpty() && probe.measuredHeight + dp(200) > screen.heightPixels
        probe.removeAllViews()
        val width = if (twoColumns) minOf(dp(1640), screen.widthPixels - dp(120)) else dp(900)
        if (twoColumns) {
            val left = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            val right = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            place(left, first); place(right, second)
            body.addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(left, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(28) })
                addView(right, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(28) })
            }, LinearLayout.LayoutParams(-1, -2))
        } else place(body, first + second)
        body.addView(close, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(24) })
        val dialog = AlertDialog.Builder(a).setView(body).create()
        close.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { onClose() }
        Dialogs.show(dialog)
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
