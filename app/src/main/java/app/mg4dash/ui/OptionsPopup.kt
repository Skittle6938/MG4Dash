package app.mg4dash.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.mg4dash.R

/**
 * A widget's own options in a popup, opened from the widget (a long press on the energy card, the gear next to the
 * OpenStreetMap map in its menu): the settings that were in Settings › Home (Marc, 17/11), in the radar page's style
 * (a title, lines of a title, a grey subtitle and an outlined control).
 */
class OptionsPopup(private val a: Activity, title: String) {
    private val d = a.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private fun col(res: Int) = Themes.color(a, res)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(30), dp(24), dp(30), dp(22)) }

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
        body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
    }

    /** OUI / NON. */
    fun toggle(title: String, sub: String?, get: () -> Boolean, set: (Boolean) -> Unit): OptionsPopup {
        lateinit var v: TextView
        fun paint() { v.text = a.getString(if (get()) R.string.yes else R.string.no); v.isSelected = get() }
        v = chip("") { set(!get()); paint() }
        paint()
        line(title, sub, v, 130)
        return this
    }

    /** A choice in the same menu as the settings'. */
    fun choice(title: String, sub: String?, labels: List<String>, current: () -> Int, onPick: (Int) -> Unit): OptionsPopup {
        lateinit var v: TextView
        fun paint() { v.text = labels[current().coerceIn(0, labels.lastIndex)] + "  ▾" }
        v = chip("") { SourceMenu.choose(a, v, labels, current()) { onPick(it); paint() } }.apply { isSelected = true }
        paint()
        line(title, sub, v, 240)
        return this
    }

    /** Shows it; [onClose] once it goes (to apply what changed). */
    fun show(onClose: () -> Unit = {}) {
        val close = chip(a.getString(R.string.st_close)) {}
        body.addView(close, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(24) })
        val dialog = AlertDialog.Builder(a).setView(body).create()
        close.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { onClose() }
        dialog.show()
        Dialogs.hideBars(dialog)
        dialog.window?.setLayout(dp(900), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
