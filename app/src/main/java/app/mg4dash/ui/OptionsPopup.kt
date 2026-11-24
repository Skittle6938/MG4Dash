package app.mg4dash.ui

import android.app.Activity
import android.app.AlertDialog
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import app.mg4dash.R

/**
 * A widget's own options in a window, opened from the widget (a long press on a card, the gear next to the
 * OpenStreetMap map in its menu). Same look as the Settings page ([Rows]): a title bar with the card's icon, its name
 * and the round close button, then groups of rows where the whole row is the touch target. It closes with the cross,
 * a touch outside or the car's back / home key. Too tall for the screen, it becomes twice as wide and what follows
 * [column] goes on the right.
 */
class OptionsPopup(private val a: Activity, private val title: String, private val icon: Int? = null) {
    private val d = a.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private fun col(res: Int) = Themes.color(a, res)
    /** What goes in each column; rows next to each other share a group. */
    private val first = mutableListOf<View>()
    private val second = mutableListOf<View>()
    private var target = first
    /** True once shown in two columns. */
    var twoColumns = false; private set
    private var closeNow: () -> Unit = {}

    /** What follows goes in the right column when the whole is too tall for the screen in one. */
    fun column(): OptionsPopup { target = second; return this }

    /** Yes / no: a switch; the whole row toggles it. */
    fun toggle(title: String, sub: String?, get: () -> Boolean, set: (Boolean) -> Unit): OptionsPopup {
        val sw = SwitchView(a).apply { on = get() }
        target += Rows.row(a, title, sub, sw) { set(!get()); sw.on = get() }
        return this
    }

    /** A box to tick, on a shorter line (lists of things to keep or leave out). */
    fun check(title: String, get: () -> Boolean, set: (Boolean) -> Unit): OptionsPopup {
        val box = CheckView(a).apply { on = get() }
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; tag = Rows.TAG_ROW; minimumHeight = dp(64)
            Rows.touchable(this); setOnClickListener { set(!get()); box.on = get() }
        }
        row.addView(box)
        row.addView(TextView(a).apply { text = title; setTextColor(col(R.color.text_value)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f) },
            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(18) })
        target += row
        return this
    }

    /** A button doing something at once ([button] on it): the window closes first. */
    fun action(title: String, sub: String?, button: String, run: () -> Unit): OptionsPopup {
        val b = Rows.actionButton(a, button) { closeNow(); run() }
        target += Rows.row(a, title, sub, b, null)
        return this
    }

    /** A short explanation, in grey, on its own line (between groups). */
    fun note(text: String): OptionsPopup {
        target += TextView(a).apply {
            this.text = text; setTextColor(col(R.color.text_sub)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setPadding(dp(4), 0, dp(4), dp(14))
        }
        return this
    }

    /** A choice in the same menu as the settings': the value in the theme's colour, the whole row opens the menu. */
    fun choice(title: String, sub: String?, labels: List<String>, current: () -> Int, onPick: (Int) -> Unit): OptionsPopup {
        val v = Rows.value(a, labels[current().coerceIn(0, labels.lastIndex)])
        target += Rows.row(a, title, sub, v) {
            SourceMenu.choose(a, v, labels, current()) { onPick(it); v.text = labels[it.coerceIn(0, labels.lastIndex)] + "  ▾" }
        }
        return this
    }

    /** The views of one column: rows next to each other in a rounded group, other views between groups. */
    private fun build(items: List<View>): LinearLayout {
        val column = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        var group: LinearLayout? = null
        for (v in items) {
            (v.parent as? ViewGroup)?.removeView(v)
            if (v.tag == Rows.TAG_ROW) {
                val g = group ?: Rows.group(a).also { group = it; column.addView(it, LinearLayout.LayoutParams(-1, -2).apply { if (column.childCount > 0) topMargin = dp(12) }) }
                Rows.add(g, v)
            } else {
                group = null
                column.addView(v, LinearLayout.LayoutParams(-1, -2).apply { if (column.childCount > 0) topMargin = dp(12) })
            }
        }
        return column
    }

    /** Shows it; [onClose] once it goes (to apply what changed). */
    fun show(onClose: () -> Unit = {}) {
        lateinit var dialog: AlertDialog
        val body = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; background = Rows.windowBackground(a); setPadding(dp(32), 0, dp(32), dp(28))
        }
        body.addView(Rows.titleBar(a, icon, title) { dialog.dismiss() }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        // One column when it fits in the screen's height (title bar and the dialog's margins: 170 dp)
        val probe = build(first + second)
        probe.measure(View.MeasureSpec.makeMeasureSpec(dp(836), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val screen = a.resources.displayMetrics
        twoColumns = second.isNotEmpty() && probe.measuredHeight + dp(170) > screen.heightPixels
        probe.removeAllViews()
        val width = if (twoColumns) minOf(dp(1640), screen.widthPixels - dp(120)) else dp(900)
        if (twoColumns) {
            // A note before every row explains the whole window: across both columns
            val lead = first.takeWhile { it.tag != Rows.TAG_ROW }
            if (lead.isNotEmpty()) body.addView(build(lead), LinearLayout.LayoutParams(-1, -2))
            body.addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(build(first.drop(lead.size)), LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(14) })
                addView(build(second), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(14) })
            }, LinearLayout.LayoutParams(-1, -2))
        } else body.addView(build(first + second), LinearLayout.LayoutParams(-1, -2))
        dialog = AlertDialog.Builder(a).setView(body).create()
        dialog.setCanceledOnTouchOutside(true)
        closeNow = { dialog.dismiss() }
        dialog.setOnDismissListener { onClose() }
        Dialogs.show(dialog)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
