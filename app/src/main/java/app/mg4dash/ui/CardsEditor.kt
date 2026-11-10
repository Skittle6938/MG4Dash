package app.mg4dash.ui

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.mg4dash.R
import app.mg4dash.system.Prefs
import app.mg4dash.ui.HomeSlots.Card

/**
 * Edit mode of the home screen (#97), opened by pinching the home screen or from Settings › Home.
 * Over each place, what it shows: a medium place shows the climate, the media or cards; the cards it
 * goes through are turned on or off with a touch, and a card taken from another place leaves it (a
 * small map of the screen on the card tells where it is now). The home screen follows at once
 * underneath. The map card's place also sets its app, the web app's its address.
 * Over the bar: Default (the home screen as it always was) and Done.
 */
object CardsEditor {
    /** A place on screen: its model and its view. */
    class Target(val slot: HomeSlots.Slot, val view: View)

    /** Map card's extra line: the map app's name and how to change it. */
    class MapApp(val name: () -> String, val pick: (() -> Unit) -> Unit)

    private const val TAG = "mg4dash_cards_editor"
    /** Home extra: open the edit mode (Settings › Home). */
    const val EXTRA_EDIT = "edit_cards"

    /** A layout chosen in the edit mode: home is rebuilt, then the edit mode opens again. */
    @Volatile var reopen = false

    fun isOpen(activity: Activity) = activity.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<View>(TAG) != null

    /** [unavailable]: cards not offered now (the power, shown in the energy card); [dock]: the bar, where the buttons go. */
    fun show(activity: Activity, layout: HomeSlots.Layout, targets: List<Target>, unavailable: Set<Card>, dock: View?,
             mapApp: MapApp?, onChange: () -> Unit) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        root.findViewWithTag<View>(TAG)?.let { root.removeView(it) }
        root.addView(Overlay(activity, layout, targets, unavailable, dock, mapApp, onChange).apply { tag = TAG }, FrameLayout.LayoutParams(-1, -1))
    }

    fun close(activity: Activity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        root.findViewWithTag<View>(TAG)?.let { root.removeView(it) }
    }

    private class Overlay(val activity: Activity, val layout: HomeSlots.Layout, val targets: List<Target>, val unavailable: Set<Card>,
                          val dock: View?, val mapApp: MapApp?, val onChange: () -> Unit) : FrameLayout(activity) {
        private val prefs = Prefs(activity)
        private val d = resources.displayMetrics.density
        private fun dp(v: Int) = (v * d).toInt()
        // Theme colours (#38, mockups v4): neutral, light in the light theme
        private val ink = Themes.color(context, R.color.text_value)
        private val sub = Themes.color(context, R.color.text_sub)
        private val title = Themes.color(context, R.color.text_title)
        private val idle = Themes.color(context, R.color.button_idle)
        private val off = Themes.color(context, R.color.text_disabled)
        private val border = Themes.color(context, R.color.panel_border)
        private val ground = Themes.color(context, R.color.bg_top)
        private val boxes = mutableListOf<Pair<Target, LinearLayout>>()
        /** Layout, Default and Done, over the bar: in a row at the bottom, in a column on the vertical bar. */
        private val vertical = dock != null && dock.height > dock.width
        private val controls = LinearLayout(context).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        private val config: HomeSlots.Config get() = HomeSlots.load(prefs, layout, unavailable)

        init {
            setBackgroundColor(ground and 0x00FFFFFF or (0xD0 shl 24))
            isClickable = true   // home underneath receives nothing while editing
            targets.forEach { t -> LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }.also { boxes += t to it; addView(it) } }
            buildControls()
            addView(controls, LayoutParams(-2, -2))
            render()
        }

        private fun buildControls() {
            /** A rounded square: its drawing or symbol, the word under it; [filled]: Done. */
            fun button(symbol: String?, icon: Drawable?, label: Int, filled: Boolean, onClick: (View) -> Unit) = TextView(context).apply {
                gravity = Gravity.CENTER; setTextColor(if (filled) idle else ink)
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    if (filled) setColor(ink) else { setColor(idle); setStroke(dp(2), title) }
                }
                val small = vertical
                text = if (symbol != null) android.text.SpannableStringBuilder(symbol).apply {
                    setSpan(android.text.style.RelativeSizeSpan(1.8f), 0, length, 0)
                    append("\n").append(context.getString(label))
                } else context.getString(label)
                if (icon != null) { setCompoundDrawablesWithIntrinsicBounds(null, icon, null, null); compoundDrawablePadding = dp(4) }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) 12f else 15f)
                setPadding(dp(4), dp(8), dp(4), dp(8))
                if (small) {
                    // A long word (Predefinito, Por defecto) is smaller rather than cut by the narrow bar
                    val room = (dock?.width ?: dp(72)) - dp(8) - dp(10)
                    val word = paint.measureText(context.getString(label))
                    if (word > room) setTextSize(TypedValue.COMPLEX_UNIT_PX, maxOf(textSize * room / word, 9 * resources.displayMetrics.scaledDensity))
                }
                setOnClickListener { onClick(it) }
            }
            val pick = button(null, LayoutPicker.glyph(context, layout, if (vertical) 34 else 42, if (vertical) 22 else 26), R.string.ly_title, false) { v ->
                LayoutPicker.show(activity, v, layout, side = vertical) { chosen ->
                    if (chosen == layout) return@show
                    // Another grid of places: home is rebuilt with it, the edit mode opens again over it
                    HomeSlots.choose(prefs, chosen)
                    reopen = true
                    close(activity)
                    Themes.recreateSmooth(activity)
                }
            }
            val reset = button("↺", null, R.string.wc_reset, false) { v ->
                SourceMenu.confirm(activity, v, context.getString(R.string.wc_reset_confirm), context.getString(R.string.wc_reset)) {
                    HomeSlots.reset(prefs, layout); render(); onChange()
                }
            }
            val done = button("✓", null, R.string.wc_done, true) { close(activity); onChange() }
            if (vertical) {
                val w = (dock?.width ?: dp(72)) - dp(8)
                controls.addView(done, LinearLayout.LayoutParams(w, -2))
                controls.addView(reset, LinearLayout.LayoutParams(w, -2).apply { topMargin = dp(14) })
                controls.addView(pick, LinearLayout.LayoutParams(w, -2).apply { topMargin = dp(14) })
                // No room for the hint on the bar: said once
                Toasts.show(context, R.string.wc_hint, long = true)
            } else {
                // The bar's own buttons hidden behind the hint and the buttons
                controls.background = GradientDrawable().apply { cornerRadius = resources.getDimension(R.dimen.radius_panel); setColor(ground) }
                controls.setPadding(dp(16), dp(4), dp(16), dp(4))
                controls.addView(TextView(context).apply {
                    text = context.getString(R.string.wc_hint); setTextColor(ink); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                    maxLines = 2
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(24) })
                listOf(pick, reset, done).forEachIndexed { i, b ->
                    controls.addView(b, LinearLayout.LayoutParams(dp(118), -1).apply { if (i > 0) leftMargin = dp(14); topMargin = dp(2); bottomMargin = dp(2) })
                }
            }
        }

        /** Each box over its place, the buttons over the bar: the home screen underneath is already laid out. */
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
            (controls.layoutParams as LayoutParams).apply {
                val bar = dock?.takeIf { it.isShown && it.width > 0 }
                if (bar == null) {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; bottomMargin = dp(20); width = -2; height = -2
                } else {
                    val at = IntArray(2).also { bar.getLocationInWindow(it) }
                    gravity = Gravity.TOP or Gravity.START
                    leftMargin = at[0] - origin[0]; topMargin = at[1] - origin[1]
                    width = bar.width; height = bar.height
                }
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }

        private fun render() {
            val c = config
            boxes.forEach { (t, box) -> fill(c, t, box) }
        }

        private fun change(ch: HomeSlots.Change) {
            when (ch.refused) {
                HomeSlots.Refusal.KEEP_ONE, HomeSlots.Refusal.NO_CARD -> Toasts.show(context, R.string.wc_keep_one)
                HomeSlots.Refusal.ONLY_CARD_THERE -> Toasts.show(context, R.string.wc_keep_one_there, long = true)
                HomeSlots.Refusal.TOO_BIG -> Toasts.show(context, R.string.wc_too_big, long = true)
                HomeSlots.Refusal.MEDIA_ALONE -> Toasts.show(context, R.string.wc_media_alone)
                null -> { HomeSlots.save(prefs, ch.config); render(); onChange() }
            }
        }

        private fun fill(c: HomeSlots.Config, t: Target, box: LinearLayout) {
            box.removeAllViews()
            box.setPadding(dp(14), dp(10), dp(14), dp(10))
            box.background = GradientDrawable().apply {
                cornerRadius = resources.getDimension(R.dimen.radius_panel)
                setColor(ground and 0x00FFFFFF or (0xF0 shl 24)); setStroke(dp(2), title, dp(10).toFloat(), dp(7).toFloat())
            }
            val id = t.slot.id
            val here = c.fill(id)
            // Every widget this place can hold (pinch mode v2): ticked ones go by in turn, in the order ticked; the
            // media is alone in its place (it uses the swipes for its sources and tracks)
            val media = here == HomeSlots.Fill.Media
            val cards = (here as? HomeSlots.Fill.Cards)?.cards.orEmpty()
            val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val flow = FlowRow(context, dp(8))
            if (HomeSlots.mediaFits(t.slot.size)) flow.addView(chip(context.getString(R.string.wc_kind_media), media, c.slotOf(HomeSlots.Fill.Media)?.takeIf { it != id }) {
                change(HomeSlots.setKind(config, id, if (media) HomeSlots.Kind.CARDS else HomeSlots.Kind.MEDIA, unavailable))
            })
            // Only the widgets that suit this place (owner's choice): the list stays short
            Card.entries.filter { it !in unavailable && HomeSlots.fits(it, t.slot.size) }.forEach { card ->
                flow.addView(chip(context.getString(card.label), card in cards, c.slotOf(card)?.takeIf { it != id }, greyed = media) {
                    if (media) Toasts.show(context, R.string.wc_media_alone) else change(HomeSlots.toggle(config, id, card, unavailable))
                })
            }
            column.addView(flow, LinearLayout.LayoutParams(-1, -2))
            fun hint(text: Int) = column.addView(TextView(context).apply {
                this.text = context.getString(text); setTextColor(sub); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setPadding(dp(4), dp(10), dp(4), 0)
            })
            if (media) hint(R.string.wc_media_alone)
            if (Card.MAP in cards) mapApp?.let { m ->
                column.addView(link(context.getString(R.string.wc_map_app), m.name()) { m.pick { render(); onChange() } })
            }
            if (Card.WEB in cards)
                column.addView(link(context.getString(R.string.wc_web_address), webSummary()) { editUrls() })
            if (Card.WEB in cards) column.addView(doubleTapRow(map = false))
            // The map card the same (#98): fixed full screen or a window over home
            if (Card.MAP in cards) column.addView(doubleTapRow(map = true))
            // How long a web page or the map's app stays awake after a touch (#98)
            if (Card.WEB in cards || Card.MAP in cards && prefs.mapApp != null)
                column.addView(wakeRow())
            // Small places: more chips than room, they scroll
            box.addView(ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(column) },
                LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(2) })
        }

        /**
         * On: ticked, filled; [elsewhere]: in another place, shown on a small map of the screen; [greyed]: too small
         * for this place (a touch says why).
         */
        private fun chip(label: String, on: Boolean, elsewhere: String?, greyed: Boolean = false, onClick: () -> Unit) = TextView(context).apply {
            text = (if (on) "✓  " else "") + label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f); maxLines = 1
            setTextColor(if (on) idle else if (greyed) off else ink)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(50); setPadding(dp(18), 0, dp(18), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(25).toFloat()
                if (on) setColor(ink) else setStroke(dp(2), if (greyed) border else title)
            }
            if (elsewhere != null) {
                setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, glyph(elsewhere), null)
                compoundDrawablePadding = dp(10)
            }
            setOnClickListener { onClick() }
        }

        /** The layout's places in miniature, [slot] filled. */
        private fun glyph(slot: String): Drawable {
            val rects = targets.map { t -> IntArray(2).also { t.view.getLocationInWindow(it) }.let { RectF(it[0].toFloat(), it[1].toFloat(), it[0] + t.view.width.toFloat(), it[1] + t.view.height.toFloat()) } }
            val all = RectF(rects.minOf { it.left }, rects.minOf { it.top }, rects.maxOf { it.right }, rects.maxOf { it.bottom })
            val cells = rects.map { r ->
                RectF((r.left - all.left) / all.width(), (r.top - all.top) / all.height(), (r.right - all.left) / all.width(), (r.bottom - all.top) / all.height())
            }
            return Glyph(cells, targets.indexOfFirst { it.slot.id == slot }, sub, ink, d)
        }

        private fun link(label: String, value: String, onClick: () -> Unit) = TextView(context).apply {
            val s = android.text.SpannableStringBuilder(label + "  ")
            val at = s.length
            s.append(value).append("  ›")
            s.setSpan(android.text.style.ForegroundColorSpan(ink), at, s.length, 0)
            text = s
            setTextColor(sub); setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(dp(4), dp(12), dp(4), dp(4))
            setOnClickListener { onClick() }
        }

        /** Double tap on the web app, or on the map card ([map]): the fixed full screen (as before) or a window detached over home. */
        private fun doubleTapRow(map: Boolean) = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(12), dp(4), 0)
            addView(TextView(context).apply { text = context.getString(if (map) R.string.wc_double_tap_app else R.string.wc_double_tap); setTextColor(sub); setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f) })
            val row = FlowRow(context, dp(8))
            listOf(false to R.string.wc_dt_full, true to R.string.wc_dt_float).forEach { (float, label) ->
                val on = if (map) prefs.mapFloat == float else prefs.webFloat == float
                row.addView(chip(context.getString(label), on, null) { if (map) prefs.mapFloat = float else prefs.webFloat = float; render(); onChange() })
            }
            addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }

        /** "Page awake: ‹ 10 s ›", the arrows one second less or more (1 to 120 s). */
        private fun wakeRow() = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), 0)
            val value = TextView(context).apply {
                setTextColor(ink); setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f); gravity = Gravity.CENTER
                text = context.getString(R.string.wc_wake_s, prefs.webWakeSeconds)
            }
            fun arrow(glyph: String, step: Int) = TextView(context).apply {
                text = glyph; setTextColor(ink); setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f); gravity = Gravity.CENTER
                minWidth = dp(56); minHeight = dp(50)
                setOnClickListener {
                    prefs.webWakeSeconds = (prefs.webWakeSeconds + step).coerceIn(1, 120)
                    value.text = context.getString(R.string.wc_wake_s, prefs.webWakeSeconds)
                }
            }
            addView(TextView(context).apply { text = context.getString(R.string.wc_wake); setTextColor(sub); setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f) })
            addView(arrow("‹", -1))
            addView(value, LinearLayout.LayoutParams(dp(64), -2))
            addView(arrow("›", 1))
        }

        /** The address shown, and how many there are. */
        private fun webSummary(): String {
            val n = prefs.webUrls.size
            val shown = prefs.webUrl.ifBlank { return context.getString(R.string.wc_web_set) }
            return if (n > 1) WebCard.name(shown) + " · " + context.getString(R.string.wc_web_count, n) else shown
        }

        /** The web app's addresses (#98): the same menu as the card's long press, without Add this page. */
        private fun editUrls() {
            WebAddresses.show(activity, onShow = { url -> prefs.webUrl = url; render(); onChange() }, onChange = { render(); onChange() })
        }

        private fun editUrl(initial: String, done: (String) -> Unit) = askUrl(activity, initial, done)
    }

    /** An address of the web app, with MG4Dash's keyboard; [done] gets it ("" when left empty). Also the web app's Other address… (#98). */
    fun askUrl(activity: Activity, initial: String, done: (String) -> Unit) {
            val context: android.content.Context = activity
            fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
            val input = android.widget.EditText(context).apply {
                setText(initial.ifBlank { "http://" }); setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
                setSelection(text.length)
            }
            val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0); addView(input) }
            android.app.AlertDialog.Builder(activity)
                .setTitle(R.string.wc_web_address).setView(box)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val v = input.text.toString().trim()
                    done(if (v == "http://" || v == "https://") "" else if (v.isNotEmpty() && !v.contains("://")) "http://$v" else v)
                }
                .setNegativeButton(R.string.cancel, null)
                .show().also { dlg ->
                    Dialogs.fix(dlg)
                    CarKeyboard.attachInDialog(input, dlg, box, onDone = { dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick() })
                }
    }

    /** The layout's places in miniature ([cells] from 0 to 1), the one at [at] filled: where a card is now. */
    private class Glyph(private val cells: List<RectF>, private val at: Int, private val line: Int, private val fill: Int, private val d: Float) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.5f * d }
        private val r = RectF()

        override fun draw(c: Canvas) {
            val b = bounds
            val gap = 1.5f * d
            cells.forEachIndexed { i, f ->
                r.set(b.left + f.left * b.width() + gap, b.top + f.top * b.height() + gap, b.left + f.right * b.width() - gap, b.top + f.bottom * b.height() - gap)
                if (i == at) { p.style = Paint.Style.FILL; p.color = fill } else { p.style = Paint.Style.STROKE; p.color = line }
                c.drawRoundRect(r, 2 * d, 2 * d, p)
            }
        }

        override fun getIntrinsicWidth() = (36 * d).toInt()
        override fun getIntrinsicHeight() = (16 * d).toInt()
        override fun setAlpha(a: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Suppress("OVERRIDE_DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
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
