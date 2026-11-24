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
    /** [settings]: a gear at the end of the row, for the options of what the row shows (the OpenStreetMap map). */
    class Row(val mark: String, val title: String, val detail: String?, val on: Boolean,
              val edit: (() -> Unit)? = null, val delete: (() -> Unit)? = null, val settings: (() -> Unit)? = null, val click: () -> Unit)

    fun show(activity: Activity, rows: List<Row>, title: String) {
        fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
        val sel = Themes.select(activity)
        val sub = Themes.color(activity, R.color.text_sub)
        lateinit var dialog: android.app.AlertDialog
        val group = Rows.group(activity)
        rows.forEach { row ->
            val r = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; tag = Rows.TAG_ROW; minimumHeight = dp(80)
                Rows.touchable(this); setOnClickListener { dialog.dismiss(); row.click() }
            }
            r.addView(TextView(activity).apply {
                text = row.mark; setTextColor(if (row.on || row.detail == null) sel else sub); setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(40), -2).apply { rightMargin = dp(16) })
            r.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    text = row.title; setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); maxLines = 1
                    setTextColor(if (row.on) sel else Themes.color(activity, R.color.text_value))
                    if (row.on) typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                })
                if (row.detail != null) addView(TextView(activity).apply {
                    text = row.detail; setTextColor(sub); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            fun end(glyph: String, action: () -> Unit) = r.addView(TextView(activity).apply {
                text = glyph; setTextColor(Themes.color(activity, R.color.text_value)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); gravity = Gravity.CENTER
                contentDescription = glyph
                background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Themes.color(activity, R.color.button_idle)) }
                setOnClickListener { dialog.dismiss(); action() }
            }, LinearLayout.LayoutParams(dp(56), dp(56)).apply { leftMargin = dp(10) })
            row.settings?.let { end("⚙", it) }
            row.edit?.let { end("✎", it) }
            row.delete?.let { end("✕", it) }
            Rows.add(group, r)
        }
        val screen = activity.resources.displayMetrics.heightPixels
        val scroll = object : android.widget.ScrollView(activity) {
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, android.view.View.MeasureSpec.makeMeasureSpec(screen - dp(190), android.view.View.MeasureSpec.AT_MOST))
        }.apply { isVerticalScrollBarEnabled = false; addView(group) }
        dialog = Windows.show(activity, title, scroll, widthDp = 760)
    }
}
