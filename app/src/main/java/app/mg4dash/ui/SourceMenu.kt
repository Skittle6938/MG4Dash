package app.mg4dash.ui

import android.app.Activity
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.mg4dash.R
import app.mg4dash.media.MediaHub

/** Audio source menu under the source name ("Radio ▾"): the active source is coloured. */
object SourceMenu {
    fun show(activity: Activity, anchor: View) {
        val d = activity.resources.displayMetrics.density
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_panel)
            setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (16 * d).toInt())   // bottom margin: the outline of the last highlighted line is no longer cut
            elevation = 12 * d
        }
        // Non-focusable window: otherwise it takes focus and the car shows its side bar and
        // banner again (full screen lost). A touch beside it still closes it.
        val popup = android.widget.PopupWindow(list, (300 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT, false).apply {
            isOutsideTouchable = true
            setOnDismissListener { Fullscreen.apply(activity) }
        }
        val cur = MediaHub.currentSource()
        MediaHub.menuSources().forEach { pkg ->
            list.addView(TextView(activity).apply {
                text = MediaHub.sourceLabel(pkg)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
                setTextColor(Themes.color(activity, if (pkg == cur) R.color.accent_action else R.color.text_value))
                setPadding((18 * d).toInt(), 0, (18 * d).toInt(), 0)
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_chip)
                isSelected = pkg == cur
                setOnClickListener { popup.dismiss(); MediaHub.switchTo(pkg) }
            }, LinearLayout.LayoutParams(-1, (60 * d).toInt()).apply { topMargin = (4 * d).toInt() })
        }
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        // Height measured explicitly: when opening downwards (music card at the top of the screen),
        // "wrap_content" height cut the bottom of the last line.
        list.measure(View.MeasureSpec.makeMeasureSpec(popup.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        popup.height = list.measuredHeight + (6 * d).toInt()
        Popups.fit(activity, popup); popup.showAsDropDown(anchor, 0, (4 * d).toInt()); Themes.track(popup)
    }

    /** Confirmation in the menu style, centred on [anchor]: [message], Cancel and [ok] (coloured). */
    fun confirm(activity: Activity, @Suppress("UNUSED_PARAMETER") anchor: View, message: String, ok: String, onOk: () -> Unit) {
        Windows.confirm(activity, ok, message, ok, danger = true, onOk = onOk)
    }

    /**
     * A question with several answers (the curve's long press, 20/11): [title], [message], then [choices] side by
     * side and Cancel last; centred on [anchor]. [onPick] gets the index of the choice, never called for Cancel.
     */
    fun ask(activity: Activity, anchor: View, title: String, message: String, choices: List<String>, onPick: (Int) -> Unit): android.widget.PopupWindow {
        val d = activity.resources.displayMetrics.density
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_panel)
            setPadding((24 * d).toInt(), (20 * d).toInt(), (24 * d).toInt(), (20 * d).toInt())
            elevation = 12 * d
            // Opaque: the curve under it does not show through the text
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Themes.color(activity, R.color.panel) or 0xFF000000.toInt())
                cornerRadius = activity.resources.getDimension(R.dimen.radius_panel)
                setStroke(maxOf(1, d.toInt()), Themes.color(activity, R.color.panel_border))
            }
        }
        val popup = android.widget.PopupWindow(box, (580 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT, false).apply {
            isOutsideTouchable = true
            setOnDismissListener { Fullscreen.apply(activity) }
        }
        box.addView(TextView(activity).apply {
            text = title; setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f); setTextColor(Themes.color(activity, R.color.text_value))
        })
        box.addView(TextView(activity).apply {
            text = message; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setTextColor(Themes.color(activity, R.color.text_title))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (6 * d).toInt() })
        val row = LinearLayout(activity)
        (choices.map { it to true } + (activity.getString(R.string.cancel) to false)).forEachIndexed { i, (label, action) ->
            row.addView(TextView(activity).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTextColor(Themes.color(activity, R.color.text_value))
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_chip)
                setOnClickListener { popup.dismiss(); if (action) onPick(i) }
            }, LinearLayout.LayoutParams(0, (52 * d).toInt(), 1f).apply { if (i > 0) marginStart = (10 * d).toInt() })
        }
        box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (16 * d).toInt() })
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        box.measure(View.MeasureSpec.makeMeasureSpec(popup.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val at = IntArray(2).also { anchor.getLocationOnScreen(it) }
        val screenW = anchor.rootView.width
        val x = (at[0] + anchor.width / 2 - popup.width / 2).coerceIn(0, (screenW - popup.width).coerceAtLeast(0))
        val y = at[1] + anchor.height / 2 - box.measuredHeight / 2
        popup.height = box.measuredHeight; Popups.fit(activity, popup)
        popup.showAtLocation(anchor.rootView, Gravity.TOP or Gravity.START, x, y.coerceIn(0, (anchor.rootView.height - popup.height).coerceAtLeast(0))); Themes.track(popup)
        return popup
    }

    /** Generic choice menu under [anchor]; [current] is coloured. */
    /**
     * A choice among [labels] in a window of its own, over the whole screen (24/11: a menu hanging from [anchor] was
     * cut at the edge of the options window holding it, on the car): the chosen one marked and in sight, the list
     * scrolling with its bar. [title] names it; by default the title of the row holding [anchor].
     */
    @Suppress("UNUSED_PARAMETER")
    fun choose(activity: Activity, anchor: View, labels: List<String>, current: Int, centred: Boolean = false, title: String? = null, onPick: (Int) -> Unit) {
        Windows.pick(activity, title ?: rowTitle(anchor) ?: "", labels, selected = current) { onPick(it) }
    }

    /** The first text of the settings row holding [v] (its title), if it is in one. */
    private fun rowTitle(v: View): String? {
        var p = v.parent as? android.view.ViewGroup
        while (p != null) {
            if (p.tag == Rows.TAG_ROW) {
                fun first(g: View): TextView? = (g as? TextView)?.takeIf { it !== v && it.text.isNotBlank() }
                    ?: (g as? android.view.ViewGroup)?.let { gg -> (0 until gg.childCount).firstNotNullOfOrNull { first(gg.getChildAt(it)) } }
                return first(p)?.text?.toString()
            }
            p = p.parent as? android.view.ViewGroup
        }
        return null
    }

    /** Prepares a card title as a source selector. */
    fun attach(activity: Activity, title: TextView) {
        val d = activity.resources.displayMetrics.density
        title.isClickable = true
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        title.setTextColor(Themes.color(activity, R.color.text_value))
        title.setPadding(0, (8 * d).toInt(), (24 * d).toInt(), (8 * d).toInt())
        title.setOnClickListener { show(activity, it) }
    }
}
