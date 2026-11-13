package app.mg4dash.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.mg4dash.R
import app.mg4dash.car.CarBridge
import app.mg4dash.system.Log
import app.mg4dash.system.Prefs
import app.mg4dash.ui.ShortcutTiles.Tile

/**
 * Shortcuts menu (setting, on by default): a finger slid down from the top edge of an MG4Dash screen pulls a panel of
 * tiles (icon, name, state read from the car) that follows the finger; let go past a third it opens, otherwise it goes
 * back. It closes when slid back up or touched beside it, and as soon as the screen goes (another app, the car's
 * camera). A long press on it chooses the tiles shown; by default only the first row.
 *
 * The panel lives in the screen's own window, so it is never over another app (Android Auto, CarPlay…), and the
 * fog light cover over MG's button copies it as the rest of the screen.
 */
@SuppressLint("StaticFieldLeak")
object ShortcutsPanel : Application.ActivityLifecycleCallbacks {
    private const val TAG = "Shortcuts"
    /** Height of the top band where the gesture starts, in dp. */
    private const val EDGE_DP = 56
    private const val COLS = 6
    private const val REFRESH_MS = 1_000L
    /** Screens without the menu (black screens, start). */
    private val EXCLUDED = setOf("ScreenOffActivity", "BootSplashActivity")

    private var panel: Panel? = null
    private val main = Handler(Looper.getMainLooper())
    /** Last states read, to show the panel at once (the car is read again in the background). */
    private val lastShown = HashMap<Tile, ShortcutTiles.Shown>()

    fun init(application: Application) { application.registerActivityLifecycleCallbacks(this) }

    private fun enabled(context: Context) = Prefs(context).shortcutsMenu

    override fun onActivityStarted(activity: Activity) {
        if (activity.javaClass.simpleName in EXCLUDED) return
        val w = activity.window ?: return
        val base = w.callback ?: return
        if (base !is Hook) w.callback = Hook(activity, base)
    }

    override fun onActivityStopped(activity: Activity) { if (panel?.activity === activity) close(now = true) }
    override fun onActivityDestroyed(activity: Activity) { if (panel?.activity === activity) close(now = true) }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    /** Closes the menu wherever it is (the car's camera came, the setting went off). */
    fun closeAll() = main.post { close(now = true) }

    private fun close(now: Boolean) {
        val p = panel ?: return
        if (now) { p.remove(); panel = null } else p.animateTo(0f) { p.remove(); if (panel === p) panel = null }
    }

    /** Watches the touches of an MG4Dash screen: a slide down from the top edge pulls the menu. */
    private class Hook(val activity: Activity, val base: Window.Callback) : Window.Callback by base {
        private val slop = ViewConfiguration.get(activity).scaledTouchSlop
        private val edge = EDGE_DP * activity.resources.displayMetrics.density
        private var downX = 0f
        private var downY = 0f
        private var candidate = false
        /** The finger moves the panel (pulling it down, or sliding it back up). */
        private var dragging: Panel? = null
        private var startProgress = 0f

        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            val open = panel?.takeIf { it.activity === activity }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; dragging = null
                    candidate = (open == null && e.y <= edge && enabled(activity)) ||
                        (open != null && open.dialogShown.not() && open.inSheet(e.rawX, e.rawY))
                }
                MotionEvent.ACTION_MOVE -> {
                    val d = dragging
                    if (d != null) { d.setProgress(startProgress + (e.rawY - downY) / d.sheetHeight()); return true }
                    if (candidate) {
                        val dy = e.rawY - downY; val dx = e.rawX - downX
                        val pulling = open == null && dy > slop * 2 && dy > Math.abs(dx) * 1.5f
                        val pushing = open != null && dy < -slop * 2 && -dy > Math.abs(dx) * 1.5f
                        if (pulling || pushing) {
                            // The screen (or the tile) under the finger forgets this touch: no click
                            base.dispatchTouchEvent(MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }.also { })
                            val p = open ?: Panel(activity).also { panel = it; Log.i(TAG, "menu pulled") }
                            dragging = p; startProgress = p.progress; downY = e.rawY
                            return true
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val d = dragging
                    if (d != null) {
                        dragging = null; candidate = false
                        val opening = startProgress < 0.5f
                        val keep = if (opening) d.progress > 0.33f else d.progress > 0.67f
                        if (keep) d.animateTo(1f) {} else close(now = false)
                        return true
                    }
                    candidate = false
                }
            }
            return base.dispatchTouchEvent(e)
        }
    }

    /** The menu over [activity]'s screen: a shade, the panel of tiles, and a box for the aids or the switch off. */
    private class Panel(val activity: Activity) {
        private val kit = Kit(activity)
        private val prefs = Prefs(activity)
        /** Theme colour; the panel and the tiles are made opaque (the theme's panels are see-through over the background). */
        private fun c(r: Int): Int = when (r) {
            R.color.panel -> panelBg
            R.color.button_idle -> androidx.core.graphics.ColorUtils.compositeColors(Themes.color(activity, R.color.button_idle), panelBg)
            else -> Themes.color(activity, r)
        }
        private val panelBg = androidx.core.graphics.ColorUtils.compositeColors(
            Themes.color(activity, R.color.panel), Themes.color(activity, R.color.bg_top) or 0xFF000000.toInt()) or 0xFF000000.toInt()
        private val decor = activity.window.decorView as ViewGroup
        private val root = FrameLayout(activity)
        private val shade = View(activity).apply { setBackgroundColor(0x73000000); alpha = 0f }
        private val sheet = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; isClickable = true }
        private val grid = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        private val headTitle = TextView(activity)
        private val headRight = TextView(activity)
        private var box: View? = null
        val dialogShown get() = box != null
        var progress = 0f; private set
        private var editing = false
        private val tileViews = HashMap<Tile, TileView>()

        init {
            val vertical = Dock.vertical(activity); val right = Dock.rightSide(activity)
            val side = kit.dp(Dock.keyboardMarginDp(activity))
            sheet.background = GradientDrawable().apply {
                setColor(c(R.color.panel)); setStroke(1, c(R.color.panel_border))
                cornerRadii = kit.dp(28).toFloat().let { r -> floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r) }
            }
            sheet.elevation = kit.dp(10).toFloat()
            sheet.setPadding(kit.dp(28), kit.dp(22), kit.dp(28), kit.dp(30))
            val head = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            headTitle.apply { setTextColor(c(R.color.text_sub)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); letterSpacing = 0.06f; isAllCaps = true }
            headRight.apply {
                setTextColor(c(R.color.text_value)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f); gravity = Gravity.CENTER
                setPadding(kit.dp(26), 0, kit.dp(26), 0)
                background = GradientDrawable().apply { setColor(c(R.color.button_idle)); cornerRadius = kit.dp(16).toFloat() }
                setOnClickListener { edit(false) }
            }
            head.addView(headTitle, LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(headRight, LinearLayout.LayoutParams(-2, kit.dp(52)))
            sheet.addView(head, LinearLayout.LayoutParams(-1, kit.dp(52)).apply { bottomMargin = kit.dp(10); leftMargin = kit.dp(6) })
            sheet.addView(grid, LinearLayout.LayoutParams(-1, -2))
            // Handle: the panel can be slid back up
            val handle = View(activity).apply {
                background = GradientDrawable().apply { setColor(c(R.color.text_disabled)); cornerRadius = kit.dp(4).toFloat() }
            }
            sheet.addView(handle, LinearLayout.LayoutParams(kit.dp(90), kit.dp(7)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = kit.dp(4) })
            // Long press on the panel: choose the tiles
            sheet.setOnLongClickListener { edit(true); true }
            shade.setOnClickListener { if (box != null) closeBox() else close(now = false) }
            root.addView(shade, FrameLayout.LayoutParams(-1, -1))
            root.addView(sheet, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
                leftMargin = if (vertical && !right) side else kit.dp(16)
                rightMargin = if (vertical && right) side else kit.dp(16)
            })
            decor.addView(root, ViewGroup.LayoutParams(-1, -1))
            render()
            setProgress(0f)
            root.post { refresh() }
        }

        fun sheetHeight(): Float {
            if (sheet.height > 0) return sheet.height.toFloat()
            val w = decor.width - (sheet.layoutParams as ViewGroup.MarginLayoutParams).let { it.leftMargin + it.rightMargin }
            sheet.measure(View.MeasureSpec.makeMeasureSpec(maxOf(w, 1), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            return maxOf(sheet.measuredHeight, 1).toFloat()
        }

        fun inSheet(x: Float, y: Float): Boolean {
            val at = IntArray(2).also { sheet.getLocationOnScreen(it) }
            return x >= at[0] && x <= at[0] + sheet.width && y >= at[1] && y <= at[1] + sheet.height
        }

        fun setProgress(p: Float) {
            progress = p.coerceIn(0f, 1f)
            sheet.translationY = -sheetHeight() * (1f - progress)
            shade.alpha = progress
        }

        /** Frame by frame (no animator: the animation scale can be 0 on the car). */
        fun animateTo(target: Float, done: () -> Unit) {
            val from = progress; val t0 = SystemClock.uptimeMillis(); val ms = 180f
            root.post(object : Runnable {
                override fun run() {
                    val k = ((SystemClock.uptimeMillis() - t0) / ms).coerceIn(0f, 1f)
                    setProgress(from + (target - from) * (1 - (1 - k) * (1 - k)))
                    if (k < 1f) root.postOnAnimation(this) else done()
                }
            })
        }

        fun remove() {
            main.removeCallbacks(tick)
            try { decor.removeView(root) } catch (_: Exception) {}
        }

        // ── Tiles ──

        private fun shownTiles(): List<Tile> {
            val chosen = ShortcutTiles.chosen(prefs)
            val all = if (editing) chosen + Tile.values().filter { it !in chosen } else chosen
            // A tile the car does not answer for is not offered (unknown yet: shown until the first reading)
            return all.filter { lastShown[it]?.available != false }
        }

        private fun render() {
            headTitle.text = activity.getString(if (editing) R.string.sc_edit_title else R.string.sc_title)
            headRight.text = activity.getString(R.string.sc_done)
            headRight.visibility = if (editing) View.VISIBLE else View.GONE
            grid.removeAllViews(); tileViews.clear()
            val chosen = ShortcutTiles.chosen(prefs)
            shownTiles().chunked(COLS).forEach { rowTiles ->
                val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                for (i in 0 until COLS) {
                    val tile = rowTiles.getOrNull(i)
                    val v: View = if (tile == null) View(activity) else TileView(tile).also { tv ->
                        tileViews[tile] = tv
                        tv.show(lastShown[tile])
                        if (editing) tv.alpha = if (tile in chosen) 1f else 0.35f
                    }
                    row.addView(v, LinearLayout.LayoutParams(0, kit.dp(138), 1f).apply { if (i > 0) leftMargin = kit.dp(16) })
                }
                grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(16) })
            }
            if (editing) grid.addView(TextView(activity).apply {
                text = activity.getString(R.string.sc_edit_hint); setTextColor(c(R.color.text_sub)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            })
            if (progress > 0f) root.post { setProgress(progress) }
        }

        private fun edit(on: Boolean) {
            if (editing == on) return
            editing = on; Log.i(TAG, "edit mode $on"); render()
        }

        private fun touched(tile: Tile) {
            if (editing) {
                ShortcutTiles.choose(prefs, ShortcutTiles.toggled(ShortcutTiles.chosen(prefs), tile)); render(); return
            }
            when (tile) {
                // A single touch switches the car off (Marc, 13/11), never while driving
                Tile.POWER -> if (!CarBridge.powerOffCar()) Toasts.show(activity, R.string.power_off_driving) else close(now = false)
                Tile.AIDS -> aidsBox()
                else -> {
                    // The new state at once, as the climate buttons; the car's reading confirms it
                    ShortcutTiles.act(activity, tile, lastShown[tile])?.let { now -> lastShown[tile] = now; tileViews[tile]?.show(now) }
                    if (tile == Tile.SCREEN || tile == Tile.AA) close(now = false)
                    else { main.postDelayed({ refresh() }, 700); main.postDelayed({ refresh() }, 1_800) }
                }
            }
        }

        private fun longPressed(tile: Tile) {
            edit(true)
        }

        @SuppressLint("ViewConstructor")
        private inner class TileView(val tile: Tile) : LinearLayout(activity) {
            private val icon = ImageView(activity).apply { setImageResource(tile.icon) }
            private val name = TextView(activity).apply { text = activity.getString(tile.label); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); gravity = Gravity.CENTER; maxLines = 1 }
            private val state = TextView(activity).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); gravity = Gravity.CENTER; maxLines = 1 }
            private val bg = GradientDrawable().apply { cornerRadius = kit.dp(22).toFloat() }

            init {
                orientation = VERTICAL; gravity = Gravity.CENTER; background = bg
                setPadding(kit.dp(6), 0, kit.dp(6), 0)
                addView(icon, LayoutParams(kit.dp(46), kit.dp(46)))
                addView(name, LayoutParams(-1, -2).apply { topMargin = kit.dp(8) })
                addView(state, LayoutParams(-1, -2).apply { topMargin = kit.dp(2) })
                isClickable = true
                setOnClickListener { touched(tile) }
                setOnLongClickListener { longPressed(tile); true }
                show(null)
            }

            /** On (or unusual state): inverted tile, light on dark or dark on light, no accent colour. */
            fun show(s: ShortcutTiles.Shown?) {
                val inv = s?.inverted == true
                val fg = c(if (inv) R.color.panel else R.color.text_value)
                bg.setColor(c(if (inv) R.color.text_value else R.color.button_idle))
                icon.setColorFilter(fg); name.setTextColor(fg)
                state.setTextColor(if (inv) fg else c(R.color.text_sub)); state.alpha = if (inv) 0.75f else 1f
                state.text = s?.state ?: ""
                // Not usable now (rear fog light with the lights off): greyed
                alpha = if (s?.disabled == true && !editing) 0.45f else if (editing) alpha else 1f
            }
        }

        // ── States read from the car ──

        private val tick = Runnable { refresh() }

        fun refresh() {
            main.removeCallbacks(tick)
            if (panel !== this) return
            val tiles = (ShortcutTiles.chosen(prefs) + Tile.values()).distinct()
            Thread {
                val readCar = tiles.associateWith { t -> try { ShortcutTiles.read(activity, t) } catch (e: Exception) { Log.w(TAG, "${t.id}: ${e.message}"); ShortcutTiles.Shown("", false, false) } }
                main.post {
                    // A state just touched stays shown until the car confirms it (or its hold ends)
                    val read = readCar.mapValues { (t, s) -> ShortcutTiles.shown(t, s) }
                    if (panel !== this) return@post
                    val availabilityChanged = read.any { (t, s) -> lastShown[t]?.available != s.available }
                    lastShown.putAll(read)
                    if (availabilityChanged) render() else tileViews.forEach { (t, v) -> v.show(read[t]) }
                    aidsRefresh?.invoke()
                    main.postDelayed(tick, REFRESH_MS)
                }
            }.start()
        }

        // ── Box: driving aids ──

        private var aidsRefresh: (() -> Unit)? = null

        /** The aids tile follows its list at once. */
        private fun aidsChanged(a: ShortcutTiles.Aids) {
            val now = ShortcutTiles.aidsShown(activity, a)
            lastShown[Tile.AIDS] = now; tileViews[Tile.AIDS]?.show(now)
        }

        private fun showBox(content: LinearLayout, width: Int) {
            closeBox()
            content.background = GradientDrawable().apply { setColor(c(R.color.panel)); setStroke(1, c(R.color.panel_border)); cornerRadius = kit.dp(28).toFloat() }
            content.elevation = kit.dp(14).toFloat()
            content.isClickable = true
            content.setPadding(kit.dp(36), kit.dp(30), kit.dp(36), kit.dp(28))
            root.addView(content, FrameLayout.LayoutParams(kit.dp(width), -2, Gravity.CENTER))
            box = content
        }

        private fun closeBox() { box?.let { root.removeView(it) }; box = null; aidsRefresh = null }

        private fun boxTitle(text: Int) = TextView(activity).apply {
            this.text = activity.getString(text); setTextColor(c(R.color.text_value)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

        private fun boxButton(text: Int, onClick: () -> Unit) = TextView(activity).apply {
            this.text = activity.getString(text); setTextColor(c(R.color.text_value)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(c(R.color.button_idle)); cornerRadius = kit.dp(18).toFloat() }
            setOnClickListener { onClick() }
        }

        private fun buttons(vararg b: TextView) = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            b.forEachIndexed { i, v -> addView(v, LinearLayout.LayoutParams(0, kit.dp(70), 1f).apply { if (i > 0) leftMargin = kit.dp(16) }) }
        }

        private fun aidsBox() {
            val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            box.addView(boxTitle(R.string.sc_adas), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            data class Row(val label: Int, val read: (ShortcutTiles.Aids) -> Boolean?, val with: (ShortcutTiles.Aids, Boolean) -> ShortcutTiles.Aids, val set: (Boolean) -> Unit)
            val rows = listOf(
                Row(R.string.sc_lane, { it.lane }, { a, v -> a.copy(lane = v) }) { ShortcutTiles.setLane(activity, it) },
                Row(R.string.sc_fcw, { it.fcw }, { a, v -> a.copy(fcw = v) }) { ShortcutTiles.setFcw(activity, it) },
                Row(R.string.sc_udw, { it.udw }, { a, v -> a.copy(udw = v) }) { ShortcutTiles.setUdw(it) },
            )
            val switches = ArrayList<Pair<Row, Switch>>()
            var aids = ShortcutTiles.Aids(null, null, null)
            rows.forEach { r ->
                val line = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                line.addView(TextView(activity).apply { text = activity.getString(r.label); setTextColor(c(R.color.text_value)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f) },
                    LinearLayout.LayoutParams(0, -2, 1f))
                val sw = Switch()
                line.addView(sw, LinearLayout.LayoutParams(kit.dp(64), kit.dp(36)))
                // Shown at once; a quick second touch starts from the state shown
                line.setOnClickListener { r.read(aids)?.let { on -> r.set(!on); sw.set(!on); aids = r.with(aids, !on); aidsChanged(aids) } }
                box.addView(line, LinearLayout.LayoutParams(-1, kit.dp(64)))
                box.addView(View(activity).apply { setBackgroundColor(c(R.color.panel_border)) }, LinearLayout.LayoutParams(-1, 1))
                switches += r to sw
            }
            box.addView(buttons(boxButton(R.string.sc_all_off) {
                switches.forEach { (r, sw) -> if (r.read(aids) == true) { r.set(false); sw.set(false); aids = r.with(aids, false) } }
                aidsChanged(aids)
            }, boxButton(R.string.sc_close) { closeBox() }), LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(24) })
            aidsRefresh = {
                Thread {
                    val a = ShortcutTiles.aids(CarBridge.state.value)
                    main.post {
                        aids = a
                        // An aid the car does not answer for is left out
                        switches.forEach { (r, sw) -> val v = r.read(a); (sw.parent as View).visibility = if (v == null) View.GONE else View.VISIBLE; if (v != null) sw.set(v) }
                    }
                }.start()
            }
            showBox(box, 640)
            aidsRefresh?.invoke()
        }

        /** Neutral switch (no accent colour): the knob on the right and the track inverted when on. */
        private inner class Switch : View(activity) {
            private var on = false
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            fun set(v: Boolean) { on = v; invalidate() }
            override fun onDraw(cv: android.graphics.Canvas) {
                val h = height.toFloat(); val w = width.toFloat(); val r = h / 2
                paint.color = c(if (on) R.color.text_value else R.color.button_idle)
                cv.drawRoundRect(0f, 0f, w, h, r, r, paint)
                paint.color = c(if (on) R.color.panel else R.color.text_sub)
                cv.drawCircle(if (on) w - r else r, r, r - kit.dp(4), paint)
            }
        }
    }
}
