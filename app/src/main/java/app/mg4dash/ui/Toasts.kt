package app.mg4dash.ui

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import android.widget.Toast
import app.mg4dash.R

/** Messages courts au style MG4Dash (couleurs du thème en cours), au lieu du style de la voiture. */
object Toasts {
    fun show(context: Context, text: CharSequence, long: Boolean = false) {
        val d = context.resources.displayMetrics.density
        val tv = TextView(context).apply {
            this.text = text; setTextColor(Themes.color(context, R.color.text_value)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setBackgroundResource(R.drawable.bg_toast); gravity = Gravity.CENTER
            setPadding((28 * d).toInt(), (14 * d).toInt(), (28 * d).toInt(), (14 * d).toInt())
        }
        Toast(context.applicationContext).apply {
            duration = if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            @Suppress("DEPRECATION") view = tv
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, (110 * d).toInt())
        }.show()
    }

    fun show(context: Context, res: Int, long: Boolean = false) = show(context, context.getString(res), long)
}
