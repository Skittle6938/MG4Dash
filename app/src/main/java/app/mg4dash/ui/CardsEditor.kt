package app.mg4dash.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.mg4dash.R
import app.mg4dash.system.Prefs

/**
 * Edit mode of the home screen's cards (#97), opened by pinching the home screen or from Settings › Home.
 * Over each widget that goes through cards, the cards it can show, on or off with a touch; the home
 * screen follows at once underneath. The map widget also sets its map app and the web app's address.
 * Done closes it.
 */
object CardsEditor {
    /** A widget of the layout on screen: its model, its view, and the cards that do not apply there. */
    class Target(val widget: WidgetCards.Widget, val view: View, val hidden: Set<WidgetCards.Card> = emptySet())

    /** Map widget's extra lines: the map app's name and how to change it. */
    class MapApp(val name: () -> String, val pick: (() -> Unit) -> Unit)

    private const val TAG = "mg4dash_cards_editor"
    /** Home extra: open the edit mode (Settings › Home). */
    const val EXTRA_EDIT = "edit_cards"

    fun isOpen(activity: Activity) = activity.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<View>(TAG) != null

    fun show(activity: Activity, targets: List<Target>, mapApp: MapApp?, onChange: () -> Unit) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        root.findViewWithTag<View>(TAG)?.let { root.removeView(it) }
        root.addView(Overlay(activity, targets, mapApp, onChange).apply { tag = TAG }, FrameLayout.LayoutParams(-1, -1))
    }

    fun close(activity: Activity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        root.findViewWithTag<View>(TAG)?.let { root.removeView(it) }
    }

    private class Overlay(val activity: Activity, val targets: List<Target>, val mapApp: MapApp?, val onChange: () -> Unit) : FrameLayout(activity) {
        private val prefs = Prefs(activity)
        private val d = resources.displayMetrics.density
        private fun dp(v: Int) = (v * d).toInt()
        private val accent = Themes.color(context, R.color.accent_action)
        private val boxes = mutableListOf<Pair<Target, LinearLayout>>()

        init {
            setBackgroundColor(0xC8000000.toInt())
            isClickable = true   // home underneath receives nothing while editing
            targets.forEach { t -> LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }.also { boxes += t to it; addView(it) } }
            // Hint and Done, at the bottom in the middle
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                addView(TextView(context).apply {
                    text = context.getString(R.string.wc_hint); setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                    gravity = Gravity.CENTER; setPadding(dp(18), dp(10), dp(18), dp(10))
                    background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(0xE6141B29.toInt()) }
                })
                addView(TextView(context).apply {
                    text = context.getString(R.string.wc_done); setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                    gravity = Gravity.CENTER; setPadding(dp(48), dp(16), dp(48), dp(16))
                    background = GradientDrawable().apply { cornerRadius = dp(30).toFloat(); setColor(accent) }
                    setOnClickListener { close(activity); onChange() }
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
            }, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(20) })
            render()
        }

        /** Each box over its widget: the home screen underneath is already laid out. */
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val origin = IntArray(2).also { getLocationInWindow(it) }
            boxes.forEach { (target, box) ->
                val at = IntArray(2).also { target.view.getLocationInWindow(it) }
                (box.layoutParams as LayoutParams).apply {
                    gravity = Gravity.TOP or Gravity.START
                    leftMargin = at[0] - origin[0]; topMargin = at[1] - origin[1]
                    width = target.view.width; height = target.view.height
                }
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }

        private fun render() = boxes.forEach { (t, box) -> fill(t, box) }

        private fun fill(t: Target, box: LinearLayout) {
            box.removeAllViews()
            box.setPadding(dp(16), dp(12), dp(16), dp(12))
            box.background = GradientDrawable().apply {
                cornerRadius = resources.getDimension(R.dimen.radius_panel)
                setColor(0xF00B1220.toInt()); setStroke(dp(3), accent, dp(10).toFloat(), dp(7).toFloat())
            }
            box.addView(TextView(context).apply {
                text = context.getString(t.widget.title).uppercase(); letterSpacing = 0.12f
                setTextColor(Themes.color(context, R.color.text_sub)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            })
            val flow = FlowRow(context, dp(10))
            t.widget.cards.filter { it !in t.hidden }.forEach { c -> flow.addView(chip(t, c)) }
            box.addView(flow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            if (t.widget == WidgetCards.Widget.MAP) {
                mapApp?.let { m ->
                    if (WidgetCards.isOn(prefs, t.widget, WidgetCards.Card.MAP))
                        box.addView(link(context.getString(R.string.wc_map_app), m.name()) { m.pick { render(); onChange() } })
                }
                if (WidgetCards.isOn(prefs, t.widget, WidgetCards.Card.WEB))
                    box.addView(link(context.getString(R.string.wc_web_address), prefs.webUrl.ifBlank { context.getString(R.string.wc_web_set) }) { editUrl() })
            }
        }

        private fun chip(t: Target, c: WidgetCards.Card) = TextView(context).apply {
            val on = WidgetCards.isOn(prefs, t.widget, c)
            text = (if (on) "✓  " else "") + context.getString(c.label)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f); maxLines = 1
            setTextColor(if (on) Color.WHITE else Themes.color(context, R.color.text_sub))
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(56); setPadding(dp(20), 0, dp(20), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                if (on) setColor(accent) else setStroke(dp(2), Themes.color(context, R.color.text_title))
            }
            setOnClickListener {
                if (!WidgetCards.toggle(prefs, t.widget, c)) Toasts.show(context, R.string.wc_keep_one)
                render(); onChange()
            }
        }

        private fun link(label: String, value: String, onClick: () -> Unit) = TextView(context).apply {
            val s = android.text.SpannableStringBuilder(label + "  ")
            val at = s.length
            s.append(value).append("  ›")
            s.setSpan(android.text.style.ForegroundColorSpan(accent), at, s.length, 0)
            text = s
            setTextColor(Themes.color(context, R.color.text_sub)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(dp(4), dp(14), dp(4), dp(6))
            setOnClickListener { onClick() }
        }

        /** Address of the web app, with MG4Dash's keyboard. */
        private fun editUrl() {
            val input = android.widget.EditText(context).apply {
                setText(prefs.webUrl.ifBlank { "http://" }); setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
                setSelection(text.length)
            }
            val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0); addView(input) }
            android.app.AlertDialog.Builder(activity)
                .setTitle(R.string.wc_web_address).setView(box)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val v = input.text.toString().trim()
                    prefs.webUrl = if (v == "http://" || v == "https://") "" else if (v.isNotEmpty() && !v.contains("://")) "http://$v" else v
                    render(); onChange()
                }
                .setNegativeButton(R.string.cancel, null)
                .show().also { dlg ->
                    Dialogs.fix(dlg)
                    CarKeyboard.attachInDialog(input, dlg, box, onDone = { dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick() })
                }
        }
    }

    /** Chips side by side, going to the next line when the width is full. */
    private class FlowRow(context: android.content.Context, private val gap: Int) : ViewGroup(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            var x = 0; var y = 0; var line = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                c.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                if (x > 0 && x + c.measuredWidth > width) { x = 0; y += line + gap; line = 0 }
                x += c.measuredWidth + gap; line = maxOf(line, c.measuredHeight)
            }
            setMeasuredDimension(width, y + line)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val width = r - l
            var x = 0; var y = 0; var line = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (x > 0 && x + c.measuredWidth > width) { x = 0; y += line + gap; line = 0 }
                c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
                x += c.measuredWidth + gap; line = maxOf(line, c.measuredHeight)
            }
        }
    }
}
