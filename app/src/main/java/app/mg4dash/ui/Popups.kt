package app.mg4dash.ui

import android.app.Activity
import android.view.View
import android.widget.PopupWindow
import android.widget.ScrollView

/**
 * The app's menus never leave the screen (Grand affichage, step 6: 1442 × 540 dp): a menu taller or wider than the
 * screen is brought within it, and its content scrolls.
 */
object Popups {
    /** Margin kept around a menu that fills the screen. */
    private const val MARGIN_DP = 8

    /** Room on [activity]'s screen for a menu: its window, less a margin on each side. */
    fun room(activity: Activity): Pair<Int, Int> {
        val d = activity.resources.displayMetrics.density
        val root = activity.window?.decorView
        val w = root?.width?.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        val h = root?.height?.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
        val m = (2 * MARGIN_DP * d).toInt()
        return (w - m) to (h - m)
    }

    /**
     * A panel drawn over home ([panel], a column: its title first, its buttons last) within the screen (Marc, 20/11: in
     * Grand affichage the Start and Close buttons of Keep climate on were below the screen): the children from [from]
     * until [until] go in a part that scrolls when the screen is too short, the title and the buttons stay in sight.
     * [margin]: kept around the panel, in pixels.
     */
    fun scrollMiddle(panel: android.widget.LinearLayout, from: Int, until: Int, margin: Int) {
        val ctx = panel.context
        val parts = (from until until).map { panel.getChildAt(it) }
        val column = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL }
        parts.forEach { v -> val lp = v.layoutParams; panel.removeView(v); column.addView(v, lp) }
        val scroll = object : ScrollView(ctx) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                val screen = panel.rootView.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
                val w = View.MeasureSpec.makeMeasureSpec(View.MeasureSpec.getSize(widthSpec), View.MeasureSpec.EXACTLY)
                // What the rest of the panel needs: its padding, its title, its buttons and their margins
                var others = panel.paddingTop + panel.paddingBottom + 2 * margin
                for (i in 0 until panel.childCount) {
                    val c = panel.getChildAt(i)
                    if (c === this || c.visibility == View.GONE) continue
                    c.measure(w, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    val lp = c.layoutParams as? android.view.ViewGroup.MarginLayoutParams
                    others += c.measuredHeight + (lp?.topMargin ?: 0) + (lp?.bottomMargin ?: 0)
                }
                val room = (screen - others).coerceAtLeast(0)
                super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(room, View.MeasureSpec.AT_MOST))
            }
        }.apply { isVerticalScrollBarEnabled = true; overScrollMode = View.OVER_SCROLL_NEVER; addView(column, android.widget.FrameLayout.LayoutParams(-1, -2)) }
        panel.addView(scroll, from, android.widget.LinearLayout.LayoutParams(-1, -2))
    }

    /** [popup] (its width and height already set) within the screen; its content scrolls when cut in height. */
    fun fit(activity: Activity, popup: PopupWindow) {
        val (w, h) = room(activity)
        if (popup.width > w) popup.width = w
        if (popup.height > h) {
            val content = popup.contentView
            if (content !is ScrollView) popup.contentView = ScrollView(activity).apply {
                isVerticalScrollBarEnabled = true; overScrollMode = android.view.View.OVER_SCROLL_NEVER
                addView(content, android.widget.FrameLayout.LayoutParams(-1, -2))
            }
            popup.height = h
        }
    }
}
