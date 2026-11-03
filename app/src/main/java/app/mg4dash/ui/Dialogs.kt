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
}
