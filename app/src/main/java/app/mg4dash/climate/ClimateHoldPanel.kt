package app.mg4dash.climate

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.mg4dash.R
import app.mg4dash.system.Prefs
import app.mg4dash.ui.Kit
import java.util.Calendar
import java.util.Locale

/**
 * Keep climate on window (long press on the climate power button): climate right away or at a
 * set time. The time is set like a wheel (swipe up / down on the digits,
 * or the arrows), with "in 30 min / 1 h / 2 h" shortcuts. On the right, the settings of the chosen
 * mode. Drawn over the page in the same window: a dialog would bring back the
 * stock system bars. If the mode is already running, the long press reopens its screen directly.
 */
object ClimateHoldPanel {
    private val TAG = Any()

    fun show(a: Activity) {
        if (ClimateHold.status.value.phase != ClimateHold.Phase.IDLE) { ClimateHold.show(a); return }
        val content = a.findViewById<ViewGroup>(android.R.id.content)
        content.findViewWithTag<View>(TAG)?.let { content.removeView(it) }
        val kit = Kit(a)
        val prefs = Prefs(a)
        val shade = FrameLayout(a).apply {
            tag = TAG
            setBackgroundColor(0xB3000000.toInt())
            isClickable = true   // the page underneath no longer receives touches
        }
        val panel = kit.panel(a.getString(R.string.dm_title)).apply {
            setPadding(kit.dp(32), kit.dp(22), kit.dp(32), kit.dp(22))
            // Solid background: the home screen must not show through
            background = GradientDrawable().apply {
                setColor(kit.color(R.color.bg_bottom)); cornerRadius = a.resources.getDimension(R.dimen.radius_panel)
                setStroke(kit.dp(1), kit.color(R.color.panel_border))
            }
        }
        shade.addView(panel, FrameLayout.LayoutParams(kit.dp(1320), -2, Gravity.CENTER))
        content.addView(shade, FrameLayout.LayoutParams(-1, -1))
        val close = { content.removeView(shade) }

        // ── When: right away / at a set time ──
        var scheduled = prefs.climateHoldScheduled
        val (modeRow, paintMode) = kit.segmented(listOf(a.getString(R.string.dm_now), a.getString(R.string.dm_at_time))) {}
        panel.addView(modeRow, kit.lp(-1, -2, t = 12))

        val body = kit.row().apply { gravity = Gravity.TOP }
        panel.addView(body, kit.lp(-1, -2, t = 16))
        // Left: the mode's main control (maximum duration, or start time)
        val left = kit.column().apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(kit.color(R.color.button_idle)); cornerRadius = kit.dp(18).toFloat() }
            setPadding(kit.dp(20), kit.dp(14), kit.dp(20), kit.dp(16))
        }
        body.addView(left, kit.lp(0, kit.dp(318), 1f))
        // Right: fine settings
        val right = kit.column()
        body.addView(right, kit.lp(0, -2, 1.05f, l = 24))

        // Maximum duration (right away)
        var idx = ClimateHold.DURATIONS.indexOf(prefs.climateHoldMaxMin).coerceAtLeast(0)
        fun fmtMax(m: Int) = when { m == 0 -> a.getString(R.string.dm_unlimited); m >= 60 -> "${m / 60} h"; else -> "$m min" }
        val maxBox = kit.column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        maxBox.addView(kit.text(a.getString(R.string.dm_max), 24f, R.color.text_sub).apply { gravity = Gravity.CENTER }, kit.lp(-1, -2))
        val maxValue = kit.text(fmtMax(ClimateHold.DURATIONS[idx]), 64f, light = true).apply { gravity = Gravity.CENTER }
        maxBox.addView(wheel(kit, maxValue) { d ->
            idx = (idx + d).coerceIn(0, ClimateHold.DURATIONS.size - 1)
            maxValue.text = fmtMax(ClimateHold.DURATIONS[idx]); prefs.climateHoldMaxMin = ClimateHold.DURATIONS[idx]
        }, kit.lp(-2, -2, t = 6))
        maxBox.addView(kit.text(a.getString(R.string.dm_max_hint), 19f, R.color.text_title).apply { gravity = Gravity.CENTER; maxLines = 2 },
            kit.lp(-1, -2, t = 6))

        // Start time (at a set time): hours and minutes as wheels, 5 min steps
        var minuteOfDay = prefs.climateHoldStartMin
        val timeBox = kit.column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        timeBox.addView(kit.text(a.getString(R.string.dm_first_start), 24f, R.color.text_sub).apply { gravity = Gravity.CENTER }, kit.lp(-1, -2))
        val hh = kit.text("", 76f, light = true).apply { gravity = Gravity.CENTER; fontFeatureSettings = "tnum" }
        val mm = kit.text("", 76f, light = true).apply { gravity = Gravity.CENTER; fontFeatureSettings = "tnum" }
        val inLabel = kit.text("", 21f, R.color.accent_cold).apply { gravity = Gravity.CENTER }
        fun minutesUntil(): Int {
            val c = Calendar.getInstance(); val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
            return Math.floorMod(minuteOfDay - now, 24 * 60)
        }
        fun paintTime() {
            hh.text = String.format(Locale.getDefault(), "%02d", minuteOfDay / 60)
            mm.text = String.format(Locale.getDefault(), "%02d", minuteOfDay % 60)
            val m = minutesUntil()
            inLabel.text = a.getString(R.string.dm_in, if (m >= 60) String.format(Locale.getDefault(), "%d h %02d", m / 60, m % 60) else "$m min")
            prefs.climateHoldStartMin = minuteOfDay
        }
        val clock = kit.row().apply { gravity = Gravity.CENTER }
        clock.addView(wheel(kit, hh, vertical = true) { d -> minuteOfDay = Math.floorMod(minuteOfDay + d * 60, 24 * 60); paintTime() })
        clock.addView(kit.text(":", 70f, R.color.text_sub, light = true), kit.lp(-2, -2, l = 6, r = 6))
        clock.addView(wheel(kit, mm, vertical = true) { d ->
            minuteOfDay = Math.floorMod(minuteOfDay / 5 * 5 + d * 5, 24 * 60); paintTime()
        })
        timeBox.addView(clock, kit.lp(-2, -2))
        timeBox.addView(inLabel, kit.lp(-2, -2, t = 2))
        // Shortcuts: in 30 min, 1 h, 2 h (rounded to 5 minutes)
        val quick = kit.row()
        listOf(30, 60, 120).forEachIndexed { i, m ->
            quick.addView(kit.toggleTile(a.getString(R.string.dm_in, if (m >= 60) "${m / 60} h" else "$m min")) {
                val c = Calendar.getInstance()
                minuteOfDay = Math.floorMod(((c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE) + m + 4) / 5) * 5, 24 * 60); paintTime()
            }.apply { textSize = 17f }, kit.lp(0, kit.dp(46), 1f, l = if (i > 0) 8 else 0))
        }
        timeBox.addView(quick, kit.lp(-1, -2, t = 10))
        paintTime()

        // Fine settings on the right
        var lockSec = prefs.climateHoldLockSec
        var runs = prefs.climateHoldRuns; var dur = prefs.climateHoldRunMin; var gap = prefs.climateHoldGapMin
        fun mins(m: Int) = if (m >= 60) String.format(Locale.getDefault(), "%d h %02d", m / 60, m % 60) else "$m min"
        val lockRow = stepper(kit, a.getString(R.string.dm_lock_delay), "$lockSec s") { d, v ->
            lockSec = (lockSec + d * 10).coerceIn(10, 300); prefs.climateHoldLockSec = lockSec; v.text = "$lockSec s" }
        val runsRow = stepper(kit, a.getString(R.string.dm_runs), "$runs") { d, v ->
            runs = (runs + d).coerceIn(1, 6); prefs.climateHoldRuns = runs; v.text = "$runs" }
        val durRow = stepper(kit, a.getString(R.string.dm_duration), mins(dur)) { d, v ->
            dur = (dur + d * 5).coerceIn(5, 60); prefs.climateHoldRunMin = dur; v.text = mins(dur) }
        val gapRow = stepper(kit, a.getString(R.string.dm_gap), mins(gap)) { d, v ->
            gap = (gap + d * 5).coerceIn(5, 240); prefs.climateHoldGapMin = gap; v.text = mins(gap) }
        val note = kit.text("", 18f, R.color.text_title).apply { maxLines = 4; setLineSpacing(0f, 1.1f) }
        // Screen off once locked: a single YES / NO button, like the options in the settings
        val offRow = kit.row()
        offRow.addView(kit.column().apply {
            addView(kit.text(a.getString(R.string.dm_screen_off), 22f, R.color.text_sub))
            addView(kit.text(a.getString(R.string.dm_screen_off_hint), 17f, R.color.text_title).apply { maxLines = 2 }, kit.lp(-1, -2, t = 2))
        }, kit.lp(0, -2, 1f))
        // A single switch: at the end, the car once off cuts the screen by itself after a while
        lateinit var offBtn: TextView
        fun paintOff() {
            offBtn.text = a.getString(if (prefs.climateHoldScreenOff) R.string.yes else R.string.no); offBtn.isSelected = prefs.climateHoldScreenOff
        }
        offBtn = kit.toggleTile("") { prefs.climateHoldScreenOff = !prefs.climateHoldScreenOff; paintOff() }.apply { textSize = 20f }
        paintOff()
        offRow.addView(offBtn, kit.lp(kit.dp(130), kit.dp(52)))
        listOf(lockRow, durRow, runsRow, gapRow, offRow).forEach { right.addView(it, kit.lp(-1, -2, b = 8)) }
        right.addView(note, kit.lp(-1, -2, t = 4))

        fun paintAll() {
            paintMode(if (scheduled) 1 else 0)
            left.removeAllViews(); left.addView(if (scheduled) timeBox else maxBox, kit.lp(-1, -2))
            listOf(durRow, runsRow, gapRow).forEach { it.visibility = if (scheduled) View.VISIBLE else View.GONE }
            note.text = a.getString(if (scheduled) R.string.dm_note_at else R.string.dm_note_now)
        }
        // The selector calls onPick back: the chips are wired once the view is built
        for (i in 0 until modeRow.childCount) modeRow.getChildAt(i).setOnClickListener {
            scheduled = i == 1; prefs.climateHoldScheduled = scheduled; paintAll()
        }
        paintAll()

        // Clearly visible warning: this mode does not protect a child or animal left alone
        panel.addView(kit.text(a.getString(R.string.dm_warning), 20f, R.color.accent_danger).apply { maxLines = 2; setLineSpacing(0f, 1.1f) },
            kit.lp(-1, -2, t = 14))
        val buttons = kit.row()
        buttons.addView(kit.toggleTile(a.getString(R.string.dm_close)) { close() }.apply { textSize = 22f }, kit.lp(0, kit.dp(62), 1f))
        buttons.addView(kit.toggleTile(a.getString(R.string.dm_start)) {
            close()
            if (scheduled) {
                // Wall clock time of the first cycle: today, or tomorrow if the time has passed
                val c = Calendar.getInstance(); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
                c.add(Calendar.MINUTE, minutesUntil())
                ClimateHold.schedule(a, c.timeInMillis, runs, dur, gap, lockSec)
            } else ClimateHold.start(a, ClimateHold.DURATIONS[idx], lockSec)
        }.apply { textSize = 22f; isSelected = true }, kit.lp(0, kit.dp(62), 1f, l = 16))
        panel.addView(buttons, kit.lp(-1, -2, t = 14))
    }

    /** Compact "label  −  value  +" line. */
    private fun stepper(kit: Kit, label: String, initial: String, onStep: (Int, TextView) -> Unit): LinearLayout {
        val line = kit.row()
        line.addView(kit.text(label, 22f, R.color.text_sub), kit.lp(0, -2, 1f))
        val v = kit.text(initial, 26f).apply { gravity = Gravity.CENTER; fontFeatureSettings = "tnum" }
        line.addView(kit.roundButton(R.drawable.ic_minus, 52) { onStep(-1, v) })
        line.addView(v, kit.lp(kit.dp(130), -2))
        line.addView(kit.roundButton(R.drawable.ic_plus, 52) { onStep(1, v) })
        return line
    }

    /**
     * Wheel: up arrow, value, down arrow; swiping on the value scrolls (one notch every
     * 36 dp), like an hour wheel. [vertical] = false: arrows on either side (duration).
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun wheel(kit: Kit, value: TextView, vertical: Boolean = false, onStep: (Int) -> Unit): View {
        fun arrow(icon: Int, d: Int) = ImageView(value.context).apply {
            setImageResource(icon); setColorFilter(kit.color(R.color.text_sub))
            scaleType = ImageView.ScaleType.FIT_CENTER
            val p = kit.dp(6); setPadding(p, p, p, p)
            setBackgroundResource(R.drawable.bg_button)
            setOnClickListener { onStep(d) }
        }
        var lastY = 0f; var acc = 0f
        val stepPx = kit.dp(36).toFloat()
        value.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastY = if (vertical) e.rawY else e.rawX; acc = 0f }
                MotionEvent.ACTION_MOVE -> {
                    val pos = if (vertical) e.rawY else e.rawX
                    acc += if (vertical) lastY - pos else pos - lastY; lastY = pos
                    while (acc >= stepPx) { onStep(1); acc -= stepPx }
                    while (acc <= -stepPx) { onStep(-1); acc += stepPx }
                }
            }
            true
        }
        return if (vertical) kit.column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(arrow(R.drawable.ic_chevron_up, 1), kit.lp(kit.dp(96), kit.dp(42)))
            addView(value, kit.lp(kit.dp(130), -2))
            addView(arrow(R.drawable.ic_chevron_down, -1), kit.lp(kit.dp(96), kit.dp(42)))
        } else kit.row().apply {
            gravity = Gravity.CENTER
            addView(arrow(R.drawable.ic_minus, -1), kit.lp(kit.dp(60), kit.dp(60)))
            addView(value, kit.lp(kit.dp(300), -2))
            addView(arrow(R.drawable.ic_plus, 1), kit.lp(kit.dp(60), kit.dp(60)))
        }
    }
}
