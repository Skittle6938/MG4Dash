package app.mg4dash.ui

import android.app.Activity
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import app.mg4dash.R

/**
 * The small menu of a long press on the web app or the map card (#98): a row per choice, the one shown
 * ticked; a touch closes it and runs the row's action.
 */
object CardMenu {
    /** [edit] and [delete]: buttons at the end of the row (an address of the web app, #98); the menu closes first. */
    class Row(val mark: String, val title: String, val detail: String?, val on: Boolean,
              val edit: (() -> Unit)? = null, val delete: (() -> Unit)? = null, val click: () -> Unit)

    fun show(activity: Activity, rows: List<Row>) {
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val accent = Themes.color(activity, R.color.accent_action)
        val sub = Themes.color(activity, R.color.text_sub)
        var dialog: android.app.Dialog? = null
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(16), dp(24), dp(8)) }
        rows.forEach { row ->
            val r = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(72); setPadding(dp(18), dp(6), dp(18), dp(6))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(if (row.on) (accent and 0x00FFFFFF) or 0x40000000 else Themes.color(activity, R.color.bg_top))
                    if (row.on) setStroke(dp(2), accent)
                }
                setOnClickListener { dialog?.dismiss(); row.click() }
            }
            r.addView(TextView(activity).apply {
                text = row.mark; setTextColor(if (row.on || row.detail == null) accent else sub); setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                setPadding(0, 0, dp(18), 0)
            })
            r.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply { text = row.title; setTextColor(Themes.color(activity, R.color.text_title)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f); maxLines = 1 })
                if (row.detail != null) addView(TextView(activity).apply {
                    text = row.detail; setTextColor(sub); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            fun end(glyph: String, action: () -> Unit) = r.addView(TextView(activity).apply {
                text = glyph; setTextColor(sub); setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f); gravity = Gravity.CENTER
                contentDescription = glyph
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL; setColor((sub and 0x00FFFFFF) or 0x30000000)
                }
                setOnClickListener { dialog?.dismiss(); action() }
            }, LinearLayout.LayoutParams(dp(60), dp(60)).apply { leftMargin = dp(10) })
            row.edit?.let { end("✎", it) }
            row.delete?.let { end("✕", it) }
            list.addView(r, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        dialog = android.app.AlertDialog.Builder(activity)
            .setView(android.widget.ScrollView(activity).apply { isVerticalScrollBarEnabled = false; addView(list) })
            .show().also { Dialogs.hideBars(it) }
        dialog.window?.setLayout(dp(560), -2)
    }
}
