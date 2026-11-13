package app.mg4dash.climate

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.mg4dash.R
import app.mg4dash.car.CarBridge
import app.mg4dash.car.VehicleState
import app.mg4dash.ui.Fullscreen
import app.mg4dash.ui.Kit
import app.mg4dash.ui.Themes
import java.util.Locale

/**
 * Keep climate on screen: very large, readable from outside through the window. Inside
 * temperature with a colour code, climate state, time remaining (or elapsed), message to passers-by,
 * battery. The screen stays on; holding the button stops the mode.
 */
class ClimateHoldActivity : AppCompatActivity() {
    private lateinit var kit: Kit
    private lateinit var inside: TextView
    private lateinit var outside: TextView
    private lateinit var state: TextView
    private lateinit var time: TextView
    private lateinit var timeLabel: TextView
    private lateinit var battery: TextView
    private lateinit var runs: TextView
    private lateinit var message: TextView
    private lateinit var blank: android.view.View
    private var displayOff = false
    private var wakeUntil = 0L
    private var brightUntil = 0L
    private var dimmed = false

    /** Minimum brightness once locked (saves the screen), normal for the time of a touch. */
    private fun dim(on: Boolean) {
        if (on == dimmed) return
        dimmed = on
        window.attributes = window.attributes.apply {
            screenBrightness = if (on) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    /** Screen cut (backlight, like the double touch on Energy) or back on. */
    private fun display(on: Boolean) {
        if (on == !displayOff) return
        displayOff = !on
        // Old SDK (#79): SystemUI's black window takes the touch that wakes the screen, ours would stay on top
        blank.visibility = if (on || app.mg4dash.system.Firmware.family == app.mg4dash.system.Firmware.Family.LEGACY) android.view.View.GONE else android.view.View.VISIBLE
        CarBridge.setDisplay(on)
    }

    /** Screen off: the first touch turns it on for 10 s, without triggering what is underneath. */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) { brightUntil = SystemClock.elapsedRealtime() + 10_000; dim(false) }
        if (displayOff) {
            if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) { display(true); wakeUntil = SystemClock.elapsedRealtime() + 10_000 }
            return true
        }
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN && wakeUntil > 0) wakeUntil = SystemClock.elapsedRealtime() + 10_000
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() { if (displayOff) CarBridge.setDisplay(true); super.onDestroy() }
    private lateinit var main: android.view.View
    private lateinit var armed: android.view.View
    private lateinit var countdown: TextView
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        // Always dark, even in day mode: less light in the cabin, less screen wear
        Themes.apply(this, dark = true)
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        kit = Kit(this)
        val root = kit.row().apply {
            setBackgroundResource(R.drawable.bg_root)
            setPadding(kit.dp(Kit.fogSafeDp(this@ClimateHoldActivity) + 24), kit.dp(28), kit.dp(40), kit.dp(28))
        }
        // Left: the inside temperature, huge
        val left = kit.column().apply { gravity = Gravity.CENTER }
        // The car does not give the cabin temperature (tests of 2026-10-20): the outside one, large
        left.addView(kit.text(getString(R.string.dm_outside_title), 34f, R.color.text_title).apply { letterSpacing = 0.15f })
        inside = kit.text("—", 230f, light = true).apply { gravity = Gravity.CENTER; fontFeatureSettings = "tnum" }
        left.addView(inside, kit.lp(-2, -2))
        outside = kit.text("", 30f, R.color.text_sub).apply { gravity = Gravity.CENTER; maxLines = 2 }
        left.addView(outside, kit.lp(-2, -2, t = 4))
        root.addView(left, kit.lp(0, -1, 1f))
        // Right: climate state, time, message to passers-by, battery, stop
        val right = kit.column().apply { gravity = Gravity.CENTER_VERTICAL }
        state = kit.text("", 58f).apply { typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) }
        right.addView(state)
        time = kit.text("", 104f, light = true).apply { fontFeatureSettings = "tnum" }
        right.addView(time, kit.lp(-2, -2, t = 4))
        timeLabel = kit.text("", 30f, R.color.text_sub)
        right.addView(timeLabel, kit.lp(-2, -2))
        // Scheduled cycles (wall clock), the current one marked by an arrow
        runs = kit.text("", 26f, R.color.text_sub).apply { maxLines = 6; setLineSpacing(0f, 1.1f) }
        right.addView(runs, kit.lp(-2, -2, t = 10))
        message = kit.text(getString(R.string.dm_message), 32f).apply { maxLines = 3; setLineSpacing(0f, 1.15f) }
        right.addView(message, kit.lp(-1, -2, t = 24))
        val bottom = kit.row()
        battery = kit.text("", 34f).apply { maxLines = 3 }
        bottom.addView(battery, kit.lp(0, -2, 1f))
        bottom.addView(kit.toggleTile(getString(R.string.dm_hold_stop)) {}.apply {
            textSize = 24f
            setOnClickListener { app.mg4dash.ui.Toasts.show(this@ClimateHoldActivity, R.string.dm_hold_stop) }
            setOnLongClickListener { ClimateHold.stop("bouton de l'écran"); finish(); true }
        }, kit.lp(kit.dp(330), kit.dp(72)))
        right.addView(bottom, kit.lp(-1, -2, t = 24))
        root.addView(right, kit.lp(0, -1, 1.25f, l = 40))
        main = root
        // Countdown before locking: the driver gets out, the two useful reminders in large text
        val arm = kit.column().apply {
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_root)
            setPadding(kit.dp(Kit.fogSafeDp(this@ClimateHoldActivity) + 24), kit.dp(20), kit.dp(40), kit.dp(20))
        }
        arm.addView(kit.text(getString(R.string.dm_lock_in), 40f, R.color.text_sub).apply { gravity = Gravity.CENTER })
        countdown = kit.text("", 200f, R.color.accent_heat, light = true).apply { gravity = Gravity.CENTER; fontFeatureSettings = "tnum" }
        arm.addView(countdown, kit.lp(-2, -2))
        arm.addView(kit.text(getString(R.string.dm_take_key), 62f, R.color.accent_danger).apply {
            gravity = Gravity.CENTER; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }, kit.lp(-2, -2, t = 4))
        arm.addView(kit.text(getString(R.string.dm_lights), 36f).apply { gravity = Gravity.CENTER; maxLines = 2 }, kit.lp(-1, -2, t = 14))
        arm.addView(kit.toggleTile(getString(R.string.dm_cancel)) { ClimateHold.stop("annulé pendant le compte à rebours"); finish() }
            .apply { textSize = 24f }, kit.lp(kit.dp(330), kit.dp(72), t = 22))
        armed = arm
        val frame = android.widget.FrameLayout(this)
        frame.addView(root, android.widget.FrameLayout.LayoutParams(-1, -1))
        frame.addView(arm, android.widget.FrameLayout.LayoutParams(-1, -1))
        // Screen off once locked (option): a black veil on top, a touch lifts it for 10 s
        blank = android.view.View(this).apply { setBackgroundColor(android.graphics.Color.BLACK); visibility = android.view.View.GONE }
        frame.addView(blank, android.widget.FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)
        Fullscreen.apply(this)
    }

    override fun onResume() {
        super.onResume()
        Fullscreen.apply(this)
        handler.post(tick)
    }

    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }

    private val tick = object : Runnable {
        override fun run() {
            val st = ClimateHold.status.value
            if (st.phase == ClimateHold.Phase.IDLE) { finish(); return }
            render(st)
            handler.postDelayed(this, 1000)
        }
    }

    private fun render(st: ClimateHold.Status) {
        val car = CarBridge.state.value
        val now = SystemClock.elapsedRealtime()
        val t = car.outsideTemp
        inside.text = t?.let { String.format(Locale.getDefault(), "%.0f°", it) } ?: "—"
        inside.setTextColor(kit.color(when {
            t == null -> R.color.text_sub
            t <= 22f -> R.color.accent_cold
            t <= 27f -> R.color.accent_energy
            t <= 32f -> R.color.accent_heat
            else -> R.color.accent_danger
        }))
        outside.text = car.driverTemp?.let { getString(R.string.dm_cabin_set, VehicleState.tempLabel(it)) } ?: ""
        val on = car.hvacOn == true
        val counting = st.phase == ClimateHold.Phase.ARMED
        val locked = st.phase == ClimateHold.Phase.WAITING || st.phase == ClimateHold.Phase.ON
        // Screen off once locked (option: sleeping inside, discretion). At the end, the car once
        // off cuts the screen by itself after a while: the screen is left as it is.
        val prefs = app.mg4dash.system.Prefs(this)
        if (locked && prefs.climateHoldScreenOff) {
            if (wakeUntil == 0L) wakeUntil = now + 3000   // time to see the locking
            if (now >= wakeUntil) display(false)
        } else if (!st.finished) display(true)
        // Screen on once locked: minimum brightness (a touch raises it for 10 s)
        if (locked && brightUntil == 0L) brightUntil = now + 3000
        dim((locked || st.finished) && now >= brightUntil)
        armed.visibility = if (counting) android.view.View.VISIBLE else android.view.View.GONE
        main.visibility = if (counting) android.view.View.GONE else android.view.View.VISIBLE
        if (counting) countdown.text = ((st.lockAt - now).coerceAtLeast(0) / 1000 + 1).toString()
        when (st.phase) {
            ClimateHold.Phase.WAITING -> {
                val next = st.clock.getOrNull(st.run)?.first
                state.text = getString(R.string.dm_clim_at, next?.let { hhmm(it) } ?: "")
                state.setTextColor(kit.color(R.color.text_value))
                time.text = left((st.runs.getOrNull(st.run)?.get(0) ?: now) - now)
                timeLabel.text = getString(R.string.dm_until_start)
                message.text = getString(R.string.dm_message_wait, next?.let { hhmm(it) } ?: "")
            }
            ClimateHold.Phase.ON -> {
                message.text = getString(R.string.dm_message)
                state.text = getString(if (on) R.string.dm_clim_on else R.string.dm_clim_off)
                state.setTextColor(kit.color(if (on) R.color.accent_cold else R.color.accent_heat))
                if (st.scheduled) { time.text = left(st.runs[st.run][1] - now); timeLabel.text = getString(R.string.dm_left) }
                else if (st.endAt > 0) { time.text = left(st.endAt - now); timeLabel.text = getString(R.string.dm_left) }
                else { time.text = left(now - st.startAt); timeLabel.text = getString(R.string.dm_since) }
            }
            ClimateHold.Phase.DONE, ClimateHold.Phase.BATTERY -> {
                state.text = getString(if (st.phase == ClimateHold.Phase.DONE) R.string.dm_state_done else R.string.dm_state_battery)
                state.setTextColor(kit.color(if (st.phase == ClimateHold.Phase.DONE) R.color.text_value else R.color.accent_danger))
                time.text = ""; timeLabel.text = ""
            }
            else -> {}
        }
        runs.visibility = if (st.scheduled) android.view.View.VISIBLE else android.view.View.GONE
        if (st.scheduled) runs.text = st.clock.mapIndexed { i, (a, b) ->
            val mark = when { st.finished || i < st.run -> "✓ "; i == st.run && st.phase == ClimateHold.Phase.ON -> "▶ "; else -> "    " }
            mark + getString(R.string.dm_run_line, i + 1, hhmm(a), hhmm(b))
        }.joinToString("\n")
        battery.text = car.batteryPercent?.let { soc ->
            // Power drawn from the battery and estimated time before 20 % (#72), smoothed to stay readable
            val kw = powerKw()
            val parts = mutableListOf<String>()
            if (kw != null && kw > 0.05f) {
                parts += String.format(java.util.Locale.getDefault(), "%.1f kW", kw)
                if (soc > 20) {
                    val cap = app.mg4dash.energy.BatteryCapacity.estimate(this)?.first ?: app.mg4dash.energy.BatteryCapacity.nominal(this)
                    val h = (soc - 20) / 100f * cap / kw
                    parts += getString(R.string.dm_to_20, left((h * 3_600_000f).toLong()))
                }
            }
            val head = getString(R.string.dm_battery, "$soc %")
            android.text.SpannableStringBuilder(head).apply {
                if (parts.isNotEmpty()) {
                    append("\n")
                    val st = length; append(parts.joinToString("  ·  "))
                    setSpan(android.text.style.RelativeSizeSpan(0.75f), st, length, 0)
                    setSpan(android.text.style.ForegroundColorSpan(kit.color(R.color.text_sub)), st, length, 0)
                }
                // 12 V battery (#90), in red below 12 V
                app.mg4dash.car.CarProps.battery12V()?.let { v ->
                    append("\n")
                    val st = length; append(getString(R.string.dm_aux_battery, String.format(java.util.Locale.getDefault(), "%.1f", v)))
                    setSpan(android.text.style.RelativeSizeSpan(0.75f), st, length, 0)
                    setSpan(android.text.style.ForegroundColorSpan(kit.color(if (v < 12f) R.color.accent_danger else R.color.text_sub)), st, length, 0)
                }
            }
        } ?: ""
    }

    private var avgKw: Float? = null
    /** Power leaving the battery (kW), moving average; null if the car does not give it. */
    private fun powerKw(): Float? {
        val v = app.mg4dash.car.CarProps.getFloat(app.mg4dash.car.CarProps.BMS_PACK_VOL)?.takeIf { it in 100f..900f } ?: return avgKw
        val i = app.mg4dash.car.CarProps.getFloat(app.mg4dash.car.CarProps.BMS_PACK_CRNT) ?: return avgKw
        val kw = v * i / 1000f
        avgKw = avgKw?.let { it + (kw - it) * 0.2f } ?: kw
        return avgKw
    }

    companion object {
        fun hhmm(wall: Long): String = app.mg4dash.system.Clock.hm(app.mg4dash.Tr.app, java.util.Date(wall))

        /** Duration: "12:34", or "1 h 05" above one hour. */
        fun left(ms: Long): String {
            val s = (ms.coerceAtLeast(0) + 999) / 1000
            return if (s >= 3600) String.format(Locale.getDefault(), "%d h %02d", s / 3600, s % 3600 / 60)
            else String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60)
        }
    }
}
