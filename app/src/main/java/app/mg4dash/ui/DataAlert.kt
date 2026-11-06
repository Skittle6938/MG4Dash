package app.mg4dash.ui

import android.app.Activity
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.mg4dash.R
import app.mg4dash.system.DataUsage

/**
 * 4G data alert (threshold reached, or MG's limit exceeded), on the home screen. Like the update
 * offer, a view placed in the home window (not a Dialog), so immersive mode stays on. Shown when
 * the reading crosses the threshold while home is in front, otherwise at the next return to home.
 */
object DataAlert {
    private var attached: java.lang.ref.WeakReference<Activity>? = null
    private val listener: () -> Unit = { attached?.get()?.let { show(it) } }

    /** Home in front: shows a waiting alert, and those that come while it stays in front. */
    fun attach(activity: Activity) {
        attached = java.lang.ref.WeakReference(activity)
        DataUsage.offAlert(listener); DataUsage.onAlert(listener)
        show(activity)
    }

    fun detach(activity: Activity) {
        if (attached?.get() === activity) { attached = null; DataUsage.offAlert(listener) }
    }

    private fun show(activity: Activity) {
        val level = DataUsage.pendingAlert ?: return
        val s = DataUsage.last ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (root.findViewWithTag<android.view.View>(TAG) != null) return
        DataUsage.pendingAlert = null
        val kit = Kit(activity)
        val scrim = FrameLayout(activity).apply {
            tag = TAG
            setBackgroundColor(Color.argb(0xB3, 0, 0, 0))
            isClickable = true; isFocusable = true
            elevation = kit.dp(24).toFloat()
        }
        val panel = kit.panel()
        // Title with an orange "!" disc
        val head = kit.row()
        head.addView(TextView(activity).apply {
            text = "!"; gravity = Gravity.CENTER; setTextColor(0xFF111111.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(kit.color(R.color.accent_heat)) }
        }, LinearLayout.LayoutParams(kit.dp(36), kit.dp(36)).apply { rightMargin = kit.dp(14) })
        val over = s.total >= DataUsage.MG_LIMIT
        head.addView(kit.text(activity.getString(if (level == 2 || over) R.string.du_alert_title_limit else R.string.du_alert_title_threshold), 26f))
        panel.addView(head)
        val thr = DataUsage.threshold(activity)
        panel.addView(kit.text(activity.getString(R.string.du_alert_text, DataUsage.format(activity, s.total), DataUsage.formatRound(activity, thr)), 19f, R.color.text_sub).apply {
            maxLines = 4; setLineSpacing(0f, 1.15f); setPadding(0, kit.dp(16), 0, 0)
        })
        val top = DataUsage.Use.values().map { it to s.of(it) }.filter { it.second > 0 }.sortedByDescending { it.second }.take(2)
        if (top.isNotEmpty()) panel.addView(kit.text(activity.getString(R.string.du_alert_mostly,
            top.joinToString(", ") { activity.getString(it.first.label) + " " + DataUsage.format(activity, it.second) }), 19f, R.color.text_sub).apply {
            maxLines = 2; setPadding(0, kit.dp(6), 0, 0)
        })
        panel.addView(QuotaBar(activity).apply { used = s.total; threshold = thr }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(16) })
        panel.addView(kit.text(activity.getString(R.string.du_alert_once), 16f, R.color.text_title).apply { maxLines = 2; setPadding(0, kit.dp(6), 0, 0) })

        fun close() { root.removeView(scrim); Fullscreen.apply(activity) }
        fun button(label: String, colorRes: Int, onClick: () -> Unit) = Button(activity).apply {
            text = label; isAllCaps = false; setTextColor(kit.color(colorRes)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setBackgroundResource(R.drawable.bg_button); minHeight = kit.dp(56); setPadding(kit.dp(20), 0, kit.dp(20), 0)
            stateListAnimator = null; elevation = 0f
            setOnClickListener { onClick() }
        }
        val buttons = kit.row()
        buttons.addView(button(activity.getString(R.string.du_alert_detail), R.color.text_value) {
            close()
            activity.startActivity(android.content.Intent(activity, app.mg4dash.settings.SettingsActivity::class.java)
                .putExtra(app.mg4dash.settings.SettingsActivity.EXTRA_TAB, app.mg4dash.settings.SettingsActivity.TAB_DATA))
        }, kit.lp(0, -1, 1f))
        buttons.addView(button(activity.getString(android.R.string.ok), R.color.accent_action) { close() }, kit.lp(0, -1, 1f, l = 10))
        panel.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(22) })
        scrim.addView(panel, FrameLayout.LayoutParams(kit.dp(820), -2, Gravity.CENTER))
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        app.mg4dash.system.Log.i("Data", "alert shown (level $level)")
    }

    private const val TAG = "data_alert"
}
