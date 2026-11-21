package app.mg4dash.ui

import app.mg4dash.system.Firmware
import app.mg4dash.system.HomeSwitcher
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
import app.mg4dash.media.MediaHub
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
    /** Height of the top band where the gesture must start, in px (the strip's, everywhere: widgets start below). */
    internal const val EDGE_PX = 12
    /**
     * On MG4Dash's own screens the band is taller (#38, "capricious" pull): a slide down starting there pulls the menu
     * rather than moving the widget under it (its taps still go through). Over the other apps only the strip's 12 px.
     */
    internal const val SCREEN_EDGE_PX = 48
    /** A pull: at least this far down (dp), and mostly vertical (sideways less than 0.7 of it). */
    private const val PULL_DP = 24

    /** Is this move from the touch down a pull of the menu? One finger, mostly down. */
    internal fun isPull(dx: Float, dy: Float, density: Float, pointers: Int): Boolean =
        pointers == 1 && dy > PULL_DP * density && Math.abs(dx) < dy * 0.7f

    /** A touch from the top band starting to go down, not yet a pull: kept from the widget under it (no swipe of its own). */
    internal fun headingDown(dx: Float, dy: Float, slop: Int): Boolean = dy > slop && Math.abs(dx) < dy

    private const val COLS = 6

    /**
     * The automatic brightness shifted, as Android's own setting does it (Settings.System screen_auto_brightness_adj,
     * -1 to 1, 0 by default): the light sensor's curve made darker or brighter, the mode staying automatic. Five steps
     * each way; by hand, − and + move the brightness by 5 %.
     */
    internal const val AUTO_ADJ = "screen_auto_brightness_adj"
    private const val ADJ_STEPS = 5
    private const val HAND_STEP = 13

    /**
     * The next shift of the automatic brightness (dir -1 or +1), kept on its steps and within -1 to 1. A value set
     * elsewhere between two steps goes to the next step in the direction touched.
     */
    internal fun adjStepped(current: Float, dir: Int): Float {
        val x = current * ADJ_STEPS
        val n = if (dir > 0) Math.floor(x + 1e-3).toInt() + 1 else Math.ceil(x - 1e-3).toInt() - 1
        return n.coerceIn(-ADJ_STEPS, ADJ_STEPS) / ADJ_STEPS.toFloat()
    }

    /** The shift shown on the AUTO button: AUTO alone at 0, else its step (AUTO +2, AUTO −1). */
    internal fun adjLabel(adj: Float): String = when (val n = Math.round(adj * ADJ_STEPS)) {
        0 -> "AUTO"
        else -> if (n > 0) "AUTO +$n" else "AUTO \u2212${-n}"
    }

    /** The brightness set by hand moved by one step (5 %), within 0 to 255. */
    internal fun handStepped(current: Int, dir: Int): Int = (current + dir * HAND_STEP).coerceIn(0, 255)
    private const val REFRESH_MS = 1_000L
    /** Screens without the menu (black screens, start). */
    private val EXCLUDED = setOf("ScreenOffActivity", "BootSplashActivity")

    internal var panel: Panel? = null
    private val main = Handler(Looper.getMainLooper())
    /** Last states read, to show the panel at once (the car is read again in the background). */
    private val lastShown = HashMap<Tile, ShortcutTiles.Shown>()

    fun init(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
        // The car's Home key (14/11) closes the menu, wherever it was pulled (over MG4Dash or another app):
        // Android tells the system dialogs to close ("homekey"), and MainActivity gets the HOME intent too
        try {
            application.registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context, i: android.content.Intent) {
                    if (closesOn(i.getStringExtra("reason")) && panel != null) { Log.i(TAG, "menu closed: ${i.getStringExtra("reason")}"); closeAll() }
                }
            }, android.content.IntentFilter(android.content.Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        } catch (e: Exception) { Log.w(TAG, "home key: ${e.message}") }
    }

    /** System dialogs closed for this [reason]: Home key, recent apps or the assistant close the menu; the others not. */
    internal fun closesOn(reason: String?): Boolean = reason == "homekey" || reason == "recentapps" || reason == "assist"

    private fun enabled(context: Context) = Prefs(context).shortcutsMenu

    override fun onActivityStarted(activity: Activity) {
        if (activity.javaClass.simpleName in EXCLUDED) return
        val w = activity.window ?: return
        val base = w.callback ?: return
        if (base !is Hook) w.callback = Hook(activity, base)
    }

    override fun onActivityStopped(activity: Activity) {
        if (panel?.activity === activity) close(now = true)
    }
    override fun onActivityDestroyed(activity: Activity) { if (panel?.activity === activity) close(now = true) }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    /** Closes the menu wherever it is (the car's camera came, the setting went off). */
    fun closeAll() = main.post { close(now = true) }

    internal fun covers(activity: Activity): Boolean = panel?.activity === activity

    internal fun close(now: Boolean) {
        val p = panel ?: return
        if (now) { p.remove(); panel = null } else p.animateTo(0f) { p.remove(); if (panel === p) panel = null }
    }

    /** Watches the touches of an MG4Dash screen: a slide down from the top edge pulls the menu. */
    private class Hook(val activity: Activity, val base: Window.Callback) : Window.Callback by base {
        private val slop = ViewConfiguration.get(activity).scaledTouchSlop
        private val density = activity.resources.displayMetrics.density
        private var downX = 0f
        private var downY = 0f
        private var candidate = false
        /** Moves kept from the screen (going down from the band): a let go then cancels its touch, no click. */
        private var withheld = false
        /** The finger moves the panel (pulling it down, or sliding it back up). */
        private var dragging: Panel? = null
        private var startProgress = 0f

        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            val open = panel?.takeIf { it.activity === activity }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; dragging = null; withheld = false
                    // From the top band of the screen (taller than the strip's): a slide down there is the menu's, the
                    // widgets' swipes start below. In full screen the strip (above this window) takes its own 12 px
                    candidate = (open == null && e.y <= SCREEN_EDGE_PX && e.pointerCount == 1 && enabled(activity)) ||
                        (open != null && !open.editMode && open.dialogShown.not() && open.inSheet(e.rawX, e.rawY))
                }
                MotionEvent.ACTION_POINTER_DOWN -> if (dragging == null) candidate = false
                MotionEvent.ACTION_MOVE -> {
                    val d = dragging
                    if (d != null) { d.setProgress(startProgress + (e.rawY - downY) / d.sheetHeight()); return true }
                    if (candidate) {
                        val dy = e.rawY - downY; val dx = e.rawX - downX
                        val pulling = open == null && isPull(dx, dy, density, e.pointerCount)
                        val pushing = open != null && dy < -slop * 2 && -dy > Math.abs(dx) * 1.5f
                        if (pulling || pushing) {
                            // The screen (or the tile) under the finger forgets this touch: no click
                            base.dispatchTouchEvent(MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }.also { })
                            val p = open ?: Panel(activity, activity, activity.window.decorView as ViewGroup).also { panel = it; Log.i(TAG, "menu pulled") }
                            dragging = p; startProgress = p.progress; downY = e.rawY
                            return true
                        }
                        // Going down from the band, not a pull yet: the widget under it does not get the move
                        if (open == null && headingDown(dx, dy, slop)) { withheld = true; return true }
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
                    if (withheld) {
                        withheld = false
                        return base.dispatchTouchEvent(MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL })
                    }
                }
            }
            return base.dispatchTouchEvent(e)
        }
    }

    /**
     * The menu: a shade, the panel of tiles, and a box for the driving aids. Hosted in [decor]: an MG4Dash screen's
     * window ([activity]) or a system window over every app (full screen, [GlobalStrip]).
     */
    internal class Panel(val ctx: Context, val activity: Activity?, private val decor: ViewGroup, private val onRemoved: () -> Unit = {}) {
        private val kit = Kit(ctx)
        private val prefs = Prefs(ctx)
        /** Theme colour; the panel and the tiles are made opaque (the theme's panels are see-through over the background). */
        private fun c(r: Int): Int = when (r) {
            R.color.panel -> panelBg
            R.color.button_idle -> androidx.core.graphics.ColorUtils.compositeColors(Themes.color(ctx, R.color.button_idle), panelBg)
            else -> Themes.color(ctx, r)
        }
        private val panelBg = androidx.core.graphics.ColorUtils.compositeColors(
            Themes.color(ctx, R.color.panel), Themes.color(ctx, R.color.bg_top) or 0xFF000000.toInt()) or 0xFF000000.toInt()
        // The theme's accent (14/11, mockup A): what is on is tinted with it, as the climate buttons
        private val light = Themes.isLight(ctx)
        private val accent = Themes.color(ctx, R.color.accent_action)
        /** Icon and state of a lit tile: the selection colour in dark, the accent itself in light (readable on white). */
        private val accentText = if (light) accent else Themes.select(ctx)
        private val accentBorder = Themes.attr(ctx, R.attr.mgActionBorder, accent)
        /** Background of a lit tile: the deepest accent in dark, a light tint of it in light. */
        private val accentTint = if (light) androidx.core.graphics.ColorUtils.blendARGB(panelBg, accent, 0.13f)
            else Themes.attr(ctx, R.attr.mgActionDeeper, androidx.core.graphics.ColorUtils.blendARGB(panelBg, accent, 0.3f))
        private val root = FrameLayout(ctx)
        private val shade = View(ctx).apply { setBackgroundColor(0x73000000); alpha = 0f }
        private val sheet = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; isClickable = true }
        private val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        private val headTitle = TextView(ctx)
        private val headRight = TextView(ctx)
        /** Title row: only while editing (its title and Done); the menu itself has none, to stay short (17/11). */
        private lateinit var headRow: View
        private var box: View? = null
        val dialogShown get() = box != null
        var progress = 0f; private set
        private var editing = false
        val editMode get() = editing
        private val tileViews = HashMap<Tile, TileView>()

        init {
            val vertical = Dock.vertical(ctx); val right = Dock.rightSide(ctx)
            val side = kit.dp(Dock.keyboardMarginDp(ctx))
            sheet.background = GradientDrawable().apply {
                setColor(c(R.color.panel)); setStroke(1, c(R.color.panel_border))
                cornerRadii = kit.dp(28).toFloat().let { r -> floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r) }
            }
            sheet.elevation = kit.dp(10).toFloat()
            sheet.setPadding(kit.dp(28), kit.dp(22), kit.dp(28), kit.dp(30))
            val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
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
            headRow = head
            sheet.addView(grid, LinearLayout.LayoutParams(-1, -2))
            // Handle: the panel can be slid back up
            val handle = View(ctx).apply {
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
            onRemoved()
        }

        // ── Tiles ──

        private fun shownTiles(): List<Tile> {
            val chosen = ShortcutTiles.chosen(prefs)
            val all = if (editing) chosen + Tile.values().filter { it !in chosen } else chosen
            // A tile the car does not answer for is not offered (unknown yet: shown until the first reading)
            return all.filter { lastShown[it]?.available != false }
        }

        private fun render() {
            headTitle.text = ctx.getString(if (editing) R.string.sc_edit_title else R.string.sc_title)
            headRight.text = ctx.getString(R.string.sc_done)
            headRight.visibility = if (editing) View.VISIBLE else View.GONE
            headRow.visibility = if (editing) View.VISIBLE else View.GONE
            grid.removeAllViews(); tileViews.clear()
            val chosen = ShortcutTiles.chosen(prefs)
            val entries = shownTiles().map { tile ->
                TileView(tile).also { tv ->
                    tileViews[tile] = tv
                    tv.show(lastShown[tile])
                    if (editing) tv.alpha = if (tile in chosen) 1f else 0.35f
                }
            }.toMutableList()
            if (editing) entries.add(0, TileView(null).apply { alpha = if (prefs.shortcutsBrightness) 1f else 0.35f })
            entries.chunked(COLS).forEach { rowTiles ->
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                for (i in 0 until COLS) {
                    val v = rowTiles.getOrNull(i) ?: View(ctx)
                    row.addView(v, LinearLayout.LayoutParams(0, kit.dp(138), 1f).apply { if (i > 0) leftMargin = kit.dp(16) })
                }
                grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(16) })
            }
            if (prefs.shortcutsBrightness && !editing) grid.addView(BrightnessBar(),
                LinearLayout.LayoutParams(-1, kit.dp(84)).apply { bottomMargin = kit.dp(16) })
            if (editing) grid.addView(TextView(ctx).apply {
                text = ctx.getString(R.string.sc_edit_hint); setTextColor(c(R.color.text_sub)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
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
                Tile.POWER -> requestPowerOff()
                Tile.AIDS -> aidsBox()
                else -> {
                    // The new state at once, as the climate buttons; the car's reading confirms it
                    ShortcutTiles.act(ctx, tile, lastShown[tile])?.let { now -> lastShown[tile] = now; tileViews[tile]?.show(now) }
                    if (tile == Tile.SCREEN || tile == Tile.AA) close(now = false)
                    else { main.postDelayed({ refresh() }, 700); main.postDelayed({ refresh() }, 1_800) }
                }
            }
        }

        private fun longPressed(tile: Tile) {
            edit(true)
        }

        @SuppressLint("ViewConstructor")
        private var powerOffPending = false

        private fun requestPowerOff() {
            if (powerOffPending) return
            powerOffPending = true
            Thread {
                val sent = CarBridge.powerOffCar()
                main.post {
                    powerOffPending = false
                    if (panel !== this) return@post
                    if (sent) close(now = false) else Toasts.show(ctx, R.string.power_off_driving)
                }
            }.start()
        }

        /** A null tile is the edit-only entry for the brightness bar. */
        private inner class TileView(val tile: Tile?) : LinearLayout(ctx) {
            private val label = tile?.label ?: R.string.br_title
            private val icon = ImageView(ctx)
            private val name = TextView(ctx).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); gravity = Gravity.CENTER; maxLines = 1 }
            private val state = TextView(ctx).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); gravity = Gravity.CENTER; maxLines = 1 }
            private val bg = GradientDrawable().apply { cornerRadius = kit.dp(22).toFloat() }

            init {
                orientation = VERTICAL; gravity = Gravity.CENTER; background = bg
                setPadding(kit.dp(6), 0, kit.dp(6), 0)
                addView(icon, LayoutParams(kit.dp(46), kit.dp(46)))
                addView(name, LayoutParams(-1, -2).apply { topMargin = kit.dp(8) })
                addView(state, LayoutParams(-1, -2).apply { topMargin = kit.dp(2) })
                isClickable = true
                setOnClickListener {
                    if (tile != null) touched(tile)
                    else { prefs.shortcutsBrightness = !prefs.shortcutsBrightness; render() }
                }
                if (tile != null) {
                    setOnLongClickListener {
                        if (!editing) { longPressed(tile); true }
                        else tile in ShortcutTiles.chosen(prefs) &&
                            startDragAndDrop(null, View.DragShadowBuilder(this), tile, 0)
                    }
                    setOnDragListener { _, event ->
                        val source = event.localState as? Tile
                        val chosen = ShortcutTiles.chosen(prefs)
                        if (!editing || source == null || source !in chosen || tile !in chosen) {
                            false
                        } else {
                            if (event.action == android.view.DragEvent.ACTION_DROP && source != tile) {
                                val reordered = chosen.toMutableList()
                                val destination = reordered.indexOf(tile)
                                reordered.remove(source)
                                reordered.add(destination, source)
                                ShortcutTiles.choose(prefs, reordered)
                                root.post { render() }
                            }
                            true
                        }
                    }
                }
                show(null)
            }

            /** On / active: background tinted with the theme's accent, its border, icon and state in the accent colour. */
            fun show(s: ShortcutTiles.Shown?) {
                val lit = tile != null && ShortcutTiles.lit(tile, s)
                bg.setColor(if (lit) accentTint else c(R.color.button_idle))
                if (lit) bg.setStroke(kit.dp(2), accentBorder) else bg.setStroke(0, 0)
                icon.setImageResource(tile?.let { ShortcutTiles.icon(it, s) } ?: R.drawable.ic_sc_sun)
                icon.setColorFilter(if (lit) accentText else c(R.color.text_value))
                name.setTextColor(c(R.color.text_value))
                name.text = s?.label ?: ctx.getString(label)
                state.setTextColor(if (lit) accentText else c(R.color.text_sub)); state.alpha = 1f
                state.text = s?.state ?: ""
                // Not usable now (rear fog light with the lights off): greyed
                alpha = if (s?.disabled == true && !editing) 0.45f else if (editing) alpha else 1f
            }
        }

        /**
         * Screen brightness, as the stock quick panel sets it (Settings.System screen_brightness, 0 to 255, and the
         * automatic mode turned off when moved by hand): small sun, track with a round knob, big sun, percentage. It
         * follows the finger live; the last value is written once more when let go. − and + around AUTO: with the
         * automatic brightness on, they shift its curve (darker / brighter, still automatic); by hand, 5 % a touch.
         */
        @SuppressLint("ViewConstructor")
        private inner class BrightnessBar : LinearLayout(ctx) {
            private val pct = TextView(ctx).apply { setTextColor(c(R.color.text_sub)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f); gravity = Gravity.END or Gravity.CENTER_VERTICAL }
            private val track = Track()
            private var value = read()
            private var lastWrite = 0L
            private val auto = TextView(ctx).apply {
                text = "AUTO"; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); letterSpacing = 0.05f
            }
            private val autoBg = GradientDrawable().apply { cornerRadius = kit.dp(16).toFloat(); setStroke(1, c(R.color.panel_border)) }
            private fun stepButton(sign: String, dir: Int) = TextView(ctx).apply {
                text = sign; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f); setTextColor(c(R.color.text_value))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                background = GradientDrawable().apply { setColor(c(R.color.panel)); cornerRadius = kit.dp(16).toFloat(); setStroke(1, c(R.color.panel_border)) }
                setOnClickListener { step(dir) }
            }
            private val minus = stepButton("\u2212", -1)
            private val plus = stepButton("+", 1)

            init {
                orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply { setColor(c(R.color.button_idle)); cornerRadius = kit.dp(22).toFloat() }
                setPadding(kit.dp(24), 0, kit.dp(24), 0)
                fun sun(size: Int) = ImageView(ctx).apply { setImageResource(R.drawable.ic_sc_sun); setColorFilter(c(R.color.text_value)) }
                addView(sun(0), LayoutParams(kit.dp(28), kit.dp(28)))
                addView(track, LayoutParams(0, -1, 1f).apply { leftMargin = kit.dp(18); rightMargin = kit.dp(18) })
                addView(sun(0), LayoutParams(kit.dp(42), kit.dp(42)))
                addView(pct, LayoutParams(kit.dp(84), -2))
                // AUTO: back to the automatic brightness, as the stock quick panel (screen_brightness_mode 1); lit while on
                addView(minus, LayoutParams(kit.dp(56), kit.dp(56)).apply { leftMargin = kit.dp(24) })
                addView(auto, LayoutParams(kit.dp(124), kit.dp(56)).apply { leftMargin = kit.dp(10) })
                addView(plus, LayoutParams(kit.dp(56), kit.dp(56)).apply { leftMargin = kit.dp(10) })
                auto.setOnClickListener {
                    // Smart brightness: it is already the automatic mode; AUTO sets the screen from its curve again at once
                    if (app.mg4dash.brightness.SmartBrightness.enabled(ctx)) { app.mg4dash.brightness.SmartBrightness.refresh(ctx); showAuto(); return@setOnClickListener }
                    try { android.provider.Settings.System.putInt(ctx.contentResolver, "screen_brightness_mode", 1); Log.i(TAG, "brightness auto") }
                    catch (e: Exception) { Log.w(TAG, "brightness auto: ${e.message}") }
                    app.mg4dash.system.BrightnessMemory.chosen(ctx)
                    showAuto()
                }
                show(); showAuto()
                isClickable = true
                setOnLongClickListener { edit(true); true }
            }

            private fun read(): Int = try { android.provider.Settings.System.getInt(ctx.contentResolver, "screen_brightness") } catch (_: Exception) { 128 }

            private fun adj(): Float = try { android.provider.Settings.System.getFloat(ctx.contentResolver, AUTO_ADJ, 0f) } catch (_: Exception) { 0f }

            /** − / +: the automatic brightness shifted while it is on, else the brightness set by hand moved by 5 %. */
            private fun step(dir: Int) {
                if (autoOn()) try {
                    val a = adjStepped(adj(), dir)
                    android.provider.Settings.System.putFloat(ctx.contentResolver, AUTO_ADJ, a)
                    Log.i(TAG, "brightness auto adjust $a")
                } catch (e: Exception) { Log.w(TAG, "brightness auto adjust: ${e.message}") }
                else { value = handStepped(read(), dir); write(value, true) }
                app.mg4dash.system.BrightnessMemory.chosen(ctx)
                show(); showAuto()
            }

            private fun autoOn(): Boolean = try { android.provider.Settings.System.getInt(ctx.contentResolver, "screen_brightness_mode", 0) == 1 } catch (_: Exception) { false }

            /** Lit as a tile that is on (accent tint, border and text) while the automatic brightness is on. */
            private fun showAuto() {
                // Smart brightness (20/11): always automatic, the slider adds a point; no − and + to shift a curve
                val smart = app.mg4dash.brightness.SmartBrightness.enabled(ctx)
                minus.visibility = if (smart) View.GONE else View.VISIBLE
                plus.visibility = if (smart) View.GONE else View.VISIBLE
                val on = smart || autoOn()
                autoBg.setColor(if (on) accentTint else c(R.color.panel))
                if (on) autoBg.setStroke(kit.dp(2), accentBorder) else autoBg.setStroke(1, c(R.color.panel_border))
                auto.setTextColor(if (on) accentText else c(R.color.text_value))
                auto.background = autoBg
                // The smart mode's small star before AUTO (the owner's choice A), star and word centred together (20/11:
                // as a compound drawable it sat at the button's far left edge)
                val star = if (smart) androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.ic_smart)?.mutate()?.apply {
                    setTint(if (on) accentText else c(R.color.text_value)); setBounds(0, 0, kit.dp(28), kit.dp(28))   // well visible: the smart mode (20/11)
                } else null
                auto.text = if (star != null) android.text.SpannableString("*AUTO").apply {
                    setSpan(CenteredIconSpan(star, kit.dp(8)), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else if (on) adjLabel(adj()) else "AUTO"
                auto.setCompoundDrawablesRelative(null, null, null, null)
            }

            /** The real settings followed: the car (or the stock panel) changing them shows here. */
            private val watch = object : android.database.ContentObserver(main) {
                override fun onChange(selfChange: Boolean) { value = read(); show(); showAuto() }
            }
            override fun onAttachedToWindow() {
                super.onAttachedToWindow()
                val cr = ctx.contentResolver
                cr.registerContentObserver(android.provider.Settings.System.getUriFor("screen_brightness_mode"), false, watch)
                cr.registerContentObserver(android.provider.Settings.System.getUriFor("screen_brightness"), false, watch)
                cr.registerContentObserver(android.provider.Settings.System.getUriFor(AUTO_ADJ), false, watch)
            }
            override fun onDetachedFromWindow() { ctx.contentResolver.unregisterContentObserver(watch); super.onDetachedFromWindow() }

            private fun show() { pct.text = "${Math.round(value * 100f / 255f)} %"; track.invalidate() }

            private fun write(v: Int, final: Boolean) {
                val now = SystemClock.uptimeMillis()
                if (!final && now - lastWrite < 60) return
                lastWrite = now
                try {
                    val cr = ctx.contentResolver
                    if (android.provider.Settings.System.getInt(cr, "screen_brightness_mode", 0) != 0) android.provider.Settings.System.putInt(cr, "screen_brightness_mode", 0)
                    android.provider.Settings.System.putInt(cr, "screen_brightness", v)
                    if (final) Log.i(TAG, "brightness $v")
                } catch (e: Exception) { Log.w(TAG, "brightness: ${e.message}") }
                // Smart brightness: the value is learnt once let go and left alone, the mode stays automatic
                app.mg4dash.brightness.SmartBrightness.userSet(ctx, v, final)
                // Kept for the next start (the car resets it), and no longer put back as the car's reset
                if (final) app.mg4dash.system.BrightnessMemory.chosen(ctx)
            }

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(e: MotionEvent): Boolean {
                val x = e.x - track.left
                if (e.actionMasked == MotionEvent.ACTION_DOWN && e.x > track.right + kit.dp(8)) return false
                val v = ((x - track.r) / (track.width - 2 * track.r)).coerceIn(0f, 1f).let { Math.round(it * 255) }
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); app.mg4dash.system.BrightnessMemory.chosen(ctx); value = v; show(); write(v, false) }
                    MotionEvent.ACTION_MOVE -> { value = v; show(); write(v, false) }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { value = v; show(); write(v, true) }
                }
                return true
            }

            /** The track: filled up to the value in the theme's accent, its knob with a soft halo. */
            private inner class Track : View(ctx) {
                val r get() = kit.dp(15).toFloat()
                private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                override fun onDraw(cv: android.graphics.Canvas) {
                    val cy = height / 2f; val h = kit.dp(10).toFloat()
                    val x = r + (width - 2 * r) * value / 255f
                    paint.color = c(R.color.panel)
                    cv.drawRoundRect(r, cy - h / 2, width - r, cy + h / 2, h / 2, h / 2, paint)
                    paint.color = accent
                    cv.drawRoundRect(r, cy - h / 2, x, cy + h / 2, h / 2, h / 2, paint)
                    paint.color = androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 0x55)
                    cv.drawCircle(x, cy, r, paint)
                    paint.color = accent
                    cv.drawCircle(x, cy, r - kit.dp(5), paint)
                }
            }
        }

        // ── States read from the car ──

        private val tick = Runnable { refresh() }

        fun refresh() {
            main.removeCallbacks(tick)
            if (panel !== this) return
            val tiles = (ShortcutTiles.chosen(prefs) + Tile.values()).distinct()
            Thread {
                val readCar = tiles.associateWith { t -> try { ShortcutTiles.read(ctx, t) } catch (e: Exception) { Log.w(TAG, "${t.id}: ${e.message}"); ShortcutTiles.Shown("", false, false) } }
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
            val now = ShortcutTiles.aidsShown(ctx, a)
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

        private fun boxTitle(text: Int) = TextView(ctx).apply {
            this.text = ctx.getString(text); setTextColor(c(R.color.text_value)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

        private fun boxButton(text: Int, onClick: () -> Unit) = TextView(ctx).apply {
            this.text = ctx.getString(text); setTextColor(c(R.color.text_value)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(c(R.color.button_idle)); cornerRadius = kit.dp(18).toFloat() }
            setOnClickListener { onClick() }
        }

        private fun buttons(vararg b: TextView) = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            b.forEachIndexed { i, v -> addView(v, LinearLayout.LayoutParams(0, kit.dp(70), 1f).apply { if (i > 0) leftMargin = kit.dp(16) }) }
        }

        private fun aidsBox() {
            val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            box.addView(boxTitle(R.string.sc_adas), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = kit.dp(8) })
            data class Row(val label: Int, val read: (ShortcutTiles.Aids) -> Boolean?, val with: (ShortcutTiles.Aids, Boolean) -> ShortcutTiles.Aids, val set: (Boolean) -> Unit)
            val rows = listOf(
                Row(R.string.sc_lane, { it.lane }, { a, v -> a.copy(lane = v) }) { ShortcutTiles.setLane(ctx, it) },
                Row(R.string.sc_fcw, { it.fcw }, { a, v -> a.copy(fcw = v) }) { ShortcutTiles.setFcw(ctx, it) },
                Row(R.string.sc_udw, { it.udw }, { a, v -> a.copy(udw = v) }) { ShortcutTiles.setUdw(it) },
            )
            val switches = ArrayList<Pair<Row, Switch>>()
            var aids = ShortcutTiles.Aids(null, null, null)
            rows.forEach { r ->
                val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                line.addView(TextView(ctx).apply { text = ctx.getString(r.label); setTextColor(c(R.color.text_value)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f) },
                    LinearLayout.LayoutParams(0, -2, 1f))
                val sw = Switch()
                line.addView(sw, LinearLayout.LayoutParams(kit.dp(64), kit.dp(36)))
                // Shown at once; a quick second touch starts from the state shown
                line.setOnClickListener { r.read(aids)?.let { on -> r.set(!on); sw.set(!on); aids = r.with(aids, !on); aidsChanged(aids) } }
                box.addView(line, LinearLayout.LayoutParams(-1, kit.dp(64)))
                box.addView(View(ctx).apply { setBackgroundColor(c(R.color.panel_border)) }, LinearLayout.LayoutParams(-1, 1))
                switches += r to sw
            }
            box.addView(buttons(boxButton(R.string.sc_all_off) {
                switches.forEach { (r, sw) -> if (r.read(aids) == true) { r.set(false); sw.set(false); aids = r.with(aids, false) } }
                aidsChanged(aids)
            }, boxButton(R.string.sc_close) { closeBox() }), LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(24) })
            // The box first: showing it closes the previous one, which also drops its refresh (the switches were
            // then never read: all shown off, touches without effect)
            showBox(box, 640)
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
            aidsRefresh?.invoke()
        }

        /** Switch: on, the knob on the right over a track in the theme's accent. */
        private inner class Switch : View(ctx) {
            private var on = false
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            fun set(v: Boolean) { on = v; invalidate() }
            override fun onDraw(cv: android.graphics.Canvas) {
                val h = height.toFloat(); val w = width.toFloat(); val r = h / 2
                paint.color = if (on) accent else c(R.color.button_idle)
                cv.drawRoundRect(0f, 0f, w, h, r, r, paint)
                paint.color = if (on) 0xFFFFFFFF.toInt() else c(R.color.text_sub)
                cv.drawCircle(if (on) w - r else r, r, r - kit.dp(4), paint)
            }
        }
    }

    /**
     * Full screen with the shortcuts menu (13/11): a strip a few pixels high, invisible, at the very top of the
     * screen above every app (Android Auto, CarPlay, radio, camera, calls, MG4Dash itself), from which a slide down
     * pulls the same menu, shown in a system window over the app in front. A touch on the strip without a slide does
     * nothing. Added once the user is unlocked, kept while the setting and the full screen are on.
     */
    object GlobalStrip {
        /** Strip height, in px of the panel. */
        private const val STRIP_PX = EDGE_PX
        /** TYPE_BOOT_PROGRESS: the highest layer an app can use here (35 on this firmware), added last so above. */
        private const val TYPE = 2021
        private var app: Context? = null
        private var strip: View? = null

        /**
         * Maintenance mode included (14/11, Discussion #111): there the strip pulls no menu, a one finger slide is
         * given back to the car's own pull-down, and a three finger slide brings [ValetBanner] to put MG4Dash back as home.
         */
        fun wanted(context: Context): Boolean = Prefs(context).let { it.valetMode || (it.shortcutsMenu && it.fullscreen) }

        /** A one finger touch handed back to the car (maintenance mode): the strip lets touches through meanwhile. */
        @Volatile private var relaying = false

        /** Published for MG4Link (Settings.Global): 1 while the strip is there, so it pulls the menu itself over its projection. */
        private const val STRIP_SETTING = "mg4dash_shortcuts_strip"
        /** Sent by MG4Link (explicit, to MG4Dash): "phase" begin / move / end / cancel, "dy" = slide in px since the touch down. */
        const val ACTION_PULL = "app.mg4dash.SHORTCUTS_PULL"
        /** From MG4Link (#113): Android Auto full screen or not, extra [EXTRA_IMMERSIVE]. */
        const val ACTION_LINK_IMMERSIVE = "app.mg4dash.LINK_IMMERSIVE"
        const val EXTRA_IMMERSIVE = "immersive"
        private var receiver: android.content.BroadcastReceiver? = null

        /** At start (unlocked) and when the menu or full screen setting changes. */
        fun update(context: Context) {
            main.post {
                val c = context.applicationContext
                app = c
                listen(c)
                if (wanted(c)) add(c) else remove()
                try { android.provider.Settings.Global.putInt(c.contentResolver, STRIP_SETTING, if (strip != null) 1 else 0) } catch (e: Exception) { Log.w(TAG, "strip setting: ${e.message}") }
            }
        }

        /**
         * MG4Link's projection in front: the strip lets touches through, so its own two and three finger gestures from the
         * top stay whole; MG4Link sees a one finger slide down from the top itself and drives the menu by [ACTION_PULL].
         */
        private val watch = object : Runnable {
            override fun run() {
                val v = strip ?: return
                if (!relaying) {
                    val link = linkInFront(v.context)
                    // MG4Link's projection under the car's status bar: the strip is the bar, no more (see [stripState])
                    val st = stripState(link, linkImmersive, Firmware.gen, link && LinkContract.carPulldown(v.context), barHeight(v.context))
                    place(st.height)
                    touchable(st.catches)
                }
                main.postDelayed(this, 400)
            }
        }

        /**
         * Height of the strip: the 12 px band at the top, and over MG4Link's projection under the car's status bar
         * ([underBar], MG4Link said immersive = false) exactly the bar (80 px on SWI173), so that a slide from the very
         * top opens the menu wherever it lands on the bar while everything that starts below the bar, in Android Auto's
         * window, stays MG4Link's: its two and three finger gestures start just under the bar (#113 regression, 17/11).
         */
        internal fun stripHeight(underBar: Boolean, barPx: Int): Int = if (underBar && barPx > 0) maxOf(barPx, EDGE_PX) else EDGE_PX

        /** The car's status bar height, read from the system (status_bar_height), 0 if unknown. */
        internal fun barHeight(c: Context): Int = try {
            val id = c.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) c.resources.getDimensionPixelSize(id) else 0
        } catch (_: Exception) { 0 }

        /**
         * Height and place of the strip: [height] px from the very top of the screen. Where the window manager put it
         * lower (under a system bar), it is moved up by as much, so that it never reaches below the height asked.
         */
        private fun place(height: Int) {
            val v = strip ?: return
            val lp = v.layoutParams as? android.view.WindowManager.LayoutParams ?: return
            val at = IntArray(2).also { v.getLocationOnScreen(it) }
            val y = if (v.isAttachedToWindow && v.height > 0 && at[1] != 0) lp.y - at[1] else lp.y
            if (lp.height == height && lp.y == y) return
            lp.height = height; lp.y = y
            try { wm(v.context).updateViewLayout(v, lp); Log.i(TAG, "strip ${height} px from the top (window moved by ${y} px, status bar ${barHeight(v.context)} px)") } catch (_: Throwable) {}
        }

        /**
         * MG4Link's projection in front, and that MG4Link pulls the menu itself (meta-data mg4dash.shortcuts_pull in its
         * manifest). The stock AllGo app or an older MG4Link (same package) does not: the strip stays as over other apps.
         */
        private fun projectionInFront(c: Context): Boolean =
            linkInFront(c).let { link -> stripMode(link, linkImmersive, Firmware.gen, link && LinkContract.carPulldown(c)) == StripGesture.Mode.PASS }

        /** Whether the front screen already belongs to MG4Dash. */
        @Suppress("DEPRECATION")
        private fun ownInFront(c: Context): Boolean = try {
            (c.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getRunningTasks(1).firstOrNull()?.topActivity?.packageName == c.packageName
        } catch (_: Exception) { false }

        /** Two fingers from the strip return from another app to MG4Dash's home. */
        private fun twoFingers(c: Context) {
            Log.i(TAG, "strip: two fingers, back to MG4Dash's home")
            try {
                c.startActivity(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
                    .setComponent(HomeSwitcher.ours(c)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) { Log.w(TAG, "home: ${e.message}") }
        }

        /** Three fingers from the strip, over MG4Link's projection: MG4Link is asked to end its session. */
        private fun threeFingers(c: Context) {
            val sent = LinkContract.sendDisconnect(c)
            Log.i(TAG, "strip: three fingers, ${if (sent) "disconnect request sent to MG4Link" else "MG4Link not installed, nothing sent"}")
        }

        /** MG4Link's projection in front, and an MG4Link that pulls the menu itself. */
        @Suppress("DEPRECATION")
        private fun linkInFront(c: Context): Boolean = try {
            (c.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getRunningTasks(1).firstOrNull()?.topActivity
                ?.let { it.packageName == MG4LINK && MediaHub.isProjectionScreen(it.className) && linkPulls(c) } == true
        } catch (_: Exception) { false }

        /**
         * Android Auto in full screen in MG4Link (its [ACTION_LINK_IMMERSIVE] broadcast), null before it says (older
         * MG4Link, or not sent yet).
         */
        @Volatile var linkImmersive: Boolean? = null

        /**
         * MG4Link's window reaches the top of the screen, so it sees a slide from there and pulls the menu itself (#113).
         * Not in full screen, the car's status bar is above Android Auto (80 px on SWI173): a finger from the top lands on
         * the bar and MG4Link never sees it, so the strip takes the slide, without touching MG4Link's two and three
         * finger gestures, which start inside its window, under the bar. Unknown: as before, except on SWI173.
         */
        internal fun linkTakesTop(immersive: Boolean?, gen: Firmware.Gen): Boolean =
            immersive ?: (gen != Firmware.Gen.SWI173)

        /** Read again when MG4Link is updated (its update time changes). */
        private var linkChecked: Pair<Long, Boolean>? = null
        private fun linkPulls(c: Context): Boolean = try {
            val pm = c.packageManager
            val updated = pm.getPackageInfo(MG4LINK, 0).lastUpdateTime
            linkChecked?.takeIf { it.first == updated }?.second ?: (pm.getApplicationInfo(MG4LINK, android.content.pm.PackageManager.GET_META_DATA)
                .metaData?.getBoolean("mg4dash.shortcuts_pull", false) == true).also { linkChecked = updated to it; Log.i(TAG, "MG4Link pulls the menu itself: $it") }
        } catch (_: Exception) { false }
        private const val MG4LINK = "com.allgo.app.androidauto"

        private fun touchable(on: Boolean) {
            val v = strip ?: return
            val lp = v.layoutParams as? android.view.WindowManager.LayoutParams ?: return
            val flag = android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            val want = if (on) lp.flags and flag.inv() else lp.flags or flag
            if (want == lp.flags) return
            lp.flags = want
            try { wm(v.context).updateViewLayout(v, lp); Log.i(TAG, "strip ${if (on) "touchable" else "lets touches through (MG4Link's projection: it pulls the menu itself)"}") } catch (_: Throwable) {}
        }

        /** The menu driven by MG4Link's slide over its projection. */
        private fun listen(c: Context) {
            if (receiver != null) return
            var dragging: Panel? = null
            var startDy = 0f
            val r = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: Context?, i: android.content.Intent?) {
                    if (i?.action == ACTION_LINK_IMMERSIVE) {
                        val on = i.getBooleanExtra(EXTRA_IMMERSIVE, false)
                        if (linkImmersive != on) Log.i(TAG, "MG4Link: Android Auto ${if (on) "full screen" else "under the status bar"}")
                        linkImmersive = on
                        return
                    }
                    if (strip == null || Prefs(c).valetMode) return
                    val dy = i?.getFloatExtra("dy", 0f) ?: 0f
                    when (i?.getStringExtra("phase")) {
                        "begin" -> if (panel == null) { val p = openPanel(c); panel = p; dragging = p; startDy = dy; Log.i(TAG, "menu pulled from MG4Link") }
                        "move" -> dragging?.let { it.setProgress((dy - startDy) / it.sheetHeight()) }
                        "end" -> { dragging?.let { it.setProgress((dy - startDy) / it.sheetHeight()); settle(it, 0f) }; dragging = null }
                        "cancel" -> { if (dragging != null) close(now = true); dragging = null }
                    }
                }
            }
            val filter = android.content.IntentFilter(ACTION_PULL).apply { addAction(ACTION_LINK_IMMERSIVE) }
            try { c.registerReceiver(r, filter); receiver = r } catch (e: Exception) { Log.w(TAG, "pull receiver: ${e.message}") }
        }

        private fun wm(c: Context) = c.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager

        private fun add(c: Context) {
            if (strip != null) return
            try {
                val v = StripView(c)
                val lp = android.view.WindowManager.LayoutParams(
                    android.view.WindowManager.LayoutParams.MATCH_PARENT, STRIP_PX, TYPE,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    android.graphics.PixelFormat.TRANSLUCENT,
                ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0; windowAnimations = 0; title = "MG4Dash shortcuts strip" }
                wm(c).addView(v, lp)
                strip = v
                Log.i(TAG, "strip added (${STRIP_PX} px, type $TYPE)")
                main.post(watch)
            } catch (e: Throwable) { Log.w(TAG, "strip: ${e.message}") }
        }

        private fun remove() {
            val v = strip ?: return
            try { wm(v.context).removeViewImmediate(v) } catch (_: Throwable) {}
            strip = null
            main.removeCallbacks(watch)
            Log.i(TAG, "strip removed")
        }

        /** A system window for the menu, over the app in front; gone with the menu. */
        private fun openPanel(c: Context): Panel {
            val themed = Themes.themed(c)
            val host = PanelWindow(themed)
            val lp = android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT, TYPE,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.START; windowAnimations = 0; title = "MG4Dash shortcuts" }
            wm(c).addView(host, lp)
            val p = Panel(themed, null, host) { try { wm(c).removeViewImmediate(host) } catch (_: Throwable) {}; Log.i(TAG, "global panel closed") }
            host.panel = p
            Log.i(TAG, "global panel open")
            return p
        }

        /** Settles a drag: open past a third (pulling) or two thirds (sliding up), closed otherwise. */
        private fun settle(p: Panel, started: Float) {
            val keep = if (started < 0.5f) p.progress > 0.33f else p.progress > 0.67f
            if (keep) p.animateTo(1f) {} else close(now = false)
        }

        /**
         * The strip: one finger down pulls the menu, which follows it; two fingers go back to MG4Dash's home, three ask
         * MG4Link to disconnect ([StripGesture]).
         */
        @SuppressLint("ViewConstructor")
        private class StripView(c: Context) : View(c) {
            private val slop = ViewConfiguration.get(c).scaledTouchSlop
            private var downX = 0f
            private var downY = 0f
            private var dragging: Panel? = null
            private val gesture = StripGesture(c.resources.displayMetrics.density)

            /** Maintenance mode, read at each touch down. */
            private var valet = false

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) valet = Prefs(context).valetMode
                if (valet) return valetTouch(e)
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; dragging = null; gesture.down(e.rawX, e.rawY) }
                    // Another finger: a two or three finger gesture, never the menu; a menu already pulled goes back
                    MotionEvent.ACTION_POINTER_DOWN -> if (gesture.pointerDown(e.pointerCount)) {
                        dragging?.let { Log.i(TAG, "strip: second finger, menu given up"); close(now = true) }
                        dragging = null
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val d = dragging
                        if (d != null) { d.setProgress((e.rawY - downY) / d.sheetHeight()); return true }
                        // rawX / rawY: the first finger's position
                        val a = gesture.move(e.rawX, e.rawY, e.pointerCount)
                        // Three fingers only over MG4Link's projection; elsewhere nothing (no session to end there)
                        val act = stripAction(a,
                            linkInFront = a == StripGesture.Action.DISCONNECT && linkInFront(context),
                            dashInFront = a == StripGesture.Action.HOME && ownInFront(context))
                        when (act) {
                            StripGesture.Action.MENU -> if (panel == null) {
                                val p = openPanel(context)
                                panel = p; dragging = p; downY = e.rawY
                            }
                            StripGesture.Action.HOME -> twoFingers(context)
                            StripGesture.Action.DISCONNECT -> threeFingers(context)
                            StripGesture.Action.NONE -> {}
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (gesture.menu) dragging?.let { settle(it, 0f) }
                        dragging = null; gesture.up()
                    }
                }
                return true
            }

            // ── Maintenance mode: the car's own pull-down kept, three fingers for the way back to MG4Dash ──

            /** Undecided, given back to the car, three fingers down, nothing more for this touch. */
            private var valetState = VALET_WAIT
            private var downTime = 0L
            private var deviceId = 0
            private var source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            private val handBack = Runnable { if (valetState == VALET_WAIT) giveBack(null) }

            private fun valetTouch(e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY; downTime = e.downTime; deviceId = e.deviceId; source = e.source
                        valetState = VALET_WAIT
                        // A finger alone and still: the car's
                        main.postDelayed(handBack, ValetGesture.WAIT_MS)
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> if (valetState == VALET_WAIT && e.pointerCount >= 3) { valetState = VALET_THREE; main.removeCallbacks(handBack) }
                    MotionEvent.ACTION_MOVE -> when (valetState) {
                        VALET_WAIT -> {
                            val dy = e.rawY - downY
                            if (e.pointerCount == 1 && Math.abs(dy) + Math.abs(e.rawX - downX) > slop) giveBack(e)
                            else if (e.pointerCount == 2 && dy > slop * 3) { valetState = VALET_DONE; main.removeCallbacks(handBack) }
                        }
                        VALET_THREE -> if (ValetGesture.isThreeFingerPull(e.rawY - downY, e.pointerCount, resources.displayMetrics.density)) {
                            valetState = VALET_DONE
                            Log.i(TAG, "maintenance mode: three finger slide, banner shown")
                            ValetBanner.show(context)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        main.removeCallbacks(handBack)
                        // A quick tap at the very top: the car's too
                        if (valetState == VALET_WAIT && e.actionMasked == MotionEvent.ACTION_UP) giveBack(null, tap = true)
                        valetState = VALET_WAIT
                    }
                }
                return true
            }

            /**
             * Gives the touch back to the car: the strip lets touches through, and the same finger is pressed again on the
             * window below (the system bar, its pull-down). Being the same touch device, the rest of the real slide then
             * goes to that window.
             */
            private fun giveBack(now: MotionEvent?, tap: Boolean = false) {
                main.removeCallbacks(handBack)
                valetState = VALET_DONE
                relaying = true
                touchable(false)
                val x = downX; val y = downY; val dev = deviceId; val src = source
                val atX = now?.rawX; val atY = now?.rawY
                // The window manager takes the change a moment later
                main.postDelayed({
                    val t = SystemClock.uptimeMillis()
                    ValetGesture.inject(context, ValetGesture.event(t, t, MotionEvent.ACTION_DOWN, x, y, dev, src))
                    if (atX != null && atY != null) ValetGesture.inject(context, ValetGesture.event(t, t, MotionEvent.ACTION_MOVE, atX, atY, dev, src))
                    if (tap) ValetGesture.inject(context, ValetGesture.event(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, dev, src))
                }, 40)
                main.postDelayed({ relaying = false; touchable(!projectionInFront(context)) }, 700)
            }
        }

        private const val VALET_WAIT = 0
        private const val VALET_THREE = 1
        private const val VALET_DONE = 2

        /** The menu's window: touches beside the panel close it (the shade), a slide up on the panel closes it too. */
        @SuppressLint("ViewConstructor")
        private class PanelWindow(c: Context) : FrameLayout(c) {
            var panel: Panel? = null
            private val slop = ViewConfiguration.get(c).scaledTouchSlop
            private var downX = 0f
            private var downY = 0f
            private var candidate = false
            private var dragging = false
            private var started = 1f

            override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
                val p = panel ?: return false
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; dragging = false; candidate = !p.editMode && !p.dialogShown && p.inSheet(e.rawX, e.rawY) }
                    MotionEvent.ACTION_MOVE -> if (candidate) {
                        val dy = e.rawY - downY; val dx = e.rawX - downX
                        if (dy < -slop * 2 && -dy > Math.abs(dx) * 1.5f) { dragging = true; started = p.progress; downY = e.rawY; return true }
                    }
                }
                return false
            }

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(e: MotionEvent): Boolean {
                val p = panel ?: return false
                if (!dragging) return super.onTouchEvent(e)
                when (e.actionMasked) {
                    MotionEvent.ACTION_MOVE -> p.setProgress(started + (e.rawY - downY) / p.sheetHeight())
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = false; settle(p, started) }
                }
                return true
            }
        }
    }
}

/** An icon drawn in the text, centred on the line, followed by [gap]: it stays with the word when the text is centred. */
internal class CenteredIconSpan(private val d: android.graphics.drawable.Drawable, private val gap: Int) : android.text.style.ReplacementSpan() {
    override fun getSize(paint: android.graphics.Paint, text: CharSequence?, start: Int, end: Int, fm: android.graphics.Paint.FontMetricsInt?): Int =
        d.bounds.width() + gap

    override fun draw(canvas: android.graphics.Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: android.graphics.Paint) {
        val fm = paint.fontMetricsInt
        // Centred on the letters' middle (between ascent and descent around the baseline)
        val mid = y + (fm.ascent + fm.descent) / 2f
        canvas.save(); canvas.translate(x, mid - d.bounds.height() / 2f); d.draw(canvas); canvas.restore()
    }
}
