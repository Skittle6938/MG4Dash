package app.mg4dash.ui

import android.app.Dialog
import android.widget.TextView
import app.mg4dash.R

/**
 * Dialog windows: the head unit replaces the layout of Android's AlertDialogs and gives the title
 * a fixed colour (dark grey), unreadable on a dark background; it is given back the theme's text colour.
 */
object Dialogs {
    fun fix(d: Dialog): Dialog {
        val id = d.context.resources.getIdentifier("alertTitle", "id", "android")
        if (id != 0) d.findViewById<TextView>(id)?.setTextColor(Themes.color(d.context, R.color.text_value))
        return d
    }

    /** A long list's dialog takes the whole screen, the car's bars staying hidden as on the page below. */
    fun fullHeight(d: Dialog) {
        d.window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        hideBars(d)
        // At the top of the screen, the title must clear MG's fog light button (x 20 → 76 dp)
        val id = d.context.resources.getIdentifier("alertTitle", "id", "android")
        d.findViewById<TextView>(id)?.apply { setPadding((64 * resources.displayMetrics.density).toInt(), paddingTop, paddingRight, paddingBottom) }
    }

    @Suppress("DEPRECATION")
    fun hideBars(d: Dialog) {
        activityOf(d.context)?.let {
            d.window?.decorView?.systemUiVisibility = it.window.decorView.systemUiVisibility and android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE.inv()
        }
    }

    /** A dialog's context wraps the activity in its theme. */
    private fun activityOf(context: android.content.Context?): android.app.Activity? = when (context) {
        is android.app.Activity -> context
        is android.content.ContextWrapper -> activityOf(context.baseContext)
        else -> null
    }

    /** Smaller title and buttons with less space around them, for a dialog whose content needs the height. */
    fun compact(d: android.app.AlertDialog) {
        val density = d.context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        fun view(name: String) = d.findViewById<android.view.View>(d.context.resources.getIdentifier(name, "id", "android"))
        view("topPanel")?.minimumHeight = 0
        view("title_template")?.setPadding(dp(24), dp(10), dp(24), dp(2))
        (view("alertTitle") as? TextView)?.textSize = 20f
        (view("buttonPanel") as? android.widget.ScrollView)?.setPadding(0, 0, 0, 0)
        listOf(android.app.AlertDialog.BUTTON_POSITIVE, android.app.AlertDialog.BUTTON_NEGATIVE).forEach { which ->
            d.getButton(which)?.apply {
                textSize = 15f; minimumHeight = 0; minHeight = 0
                setPadding(dp(16), dp(8), dp(16), dp(8))
                (parent as? android.view.ViewGroup)?.setPadding(dp(8), 0, dp(8), dp(4))
            }
        }
    }
}
