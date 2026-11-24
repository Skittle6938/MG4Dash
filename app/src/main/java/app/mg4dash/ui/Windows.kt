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
 * Windows in the settings' look ([Rows], 24/11): the title bar with the round close button, the content, and, for a
 * question, its answers at the bottom right. They close with the cross, a touch outside or the car's back / home key:
 * no "Close" button.
 */
object Windows {
    private fun dp(a: Activity, v: Int) = (v * a.resources.displayMetrics.density).toInt()

    /** A window around [content]; [buttons] (optional) at the bottom right. */
    fun show(a: Activity, title: String, content: View?, icon: Int? = null, widthDp: Int = 900, sub: String? = null,
             buttons: List<View> = emptyList(), onDismiss: () -> Unit = {}): AlertDialog {
        lateinit var dialog: AlertDialog
        val body = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; background = Rows.windowBackground(a); setPadding(dp(a, 32), 0, dp(a, 32), dp(a, 28))
        }
        body.addView(Rows.titleBar(a, icon, title, sub) { dialog.dismiss() }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(a, 4) })
        content?.let { body.addView(it, it.layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(-1, -2)) }
        if (buttons.isNotEmpty()) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
            buttons.forEachIndexed { i, b -> row.addView(b, LinearLayout.LayoutParams(-2, dp(a, 56)).apply { if (i > 0) leftMargin = dp(a, 14) }) }
            body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(a, 24) })
        }
        dialog = AlertDialog.Builder(a).setView(body).create()
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener { onDismiss() }
        Dialogs.show(dialog)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.window?.setLayout(minOf(dp(a, widthDp), a.resources.displayMetrics.widthPixels - dp(a, 80)), ViewGroup.LayoutParams.WRAP_CONTENT)
        return dialog
    }

    /** Grey text for a window's message. */
    fun message(a: Activity, text: CharSequence) = TextView(a).apply {
        this.text = text; setTextColor(Themes.color(a, R.color.text_sub)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); setLineSpacing(0f, 1.15f)
    }

    /**
     * The one confirmation: [title], [message], Cancel (plain) and [ok], in red when it erases or turns off something
     * ([danger]), in the theme's colour otherwise.
     */
    fun confirm(a: Activity, title: String, message: CharSequence?, ok: String, danger: Boolean = true, onOk: () -> Unit): AlertDialog {
        lateinit var d: AlertDialog
        val cancel = Rows.ghostButton(a, a.getString(R.string.cancel)) { d.dismiss() }
        val go = Rows.actionButton(a, ok, danger) { d.dismiss(); onOk() }
        d = show(a, title, message?.let { message(a, it) }, widthDp = 680, buttons = listOf(cancel, go))
        return d
    }
}
