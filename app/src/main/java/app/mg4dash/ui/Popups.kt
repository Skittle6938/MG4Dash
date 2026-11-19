package app.mg4dash.ui

import android.app.Activity
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
