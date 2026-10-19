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
import app.mg4dash.ui.Fullscreen
import app.mg4dash.ui.Kit
import app.mg4dash.ui.Themes
import java.util.Locale

/**
 * Écran du mode chien : très gros, lisible depuis l'extérieur à travers la vitre. Température
 * intérieure avec un code couleur, état de la clim, temps restant (ou écoulé), message aux passants,
 * batterie. L'écran reste allumé ; maintenir le bouton arrête le mode.
 */
class DogModeActivity : AppCompatActivity() {
    private lateinit var kit: Kit
    private lateinit var inside: TextView
    private lateinit var outside: TextView
    private lateinit var state: TextView
    private lateinit var time: TextView
    private lateinit var timeLabel: TextView
    private lateinit var battery: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var cabin: Float? = null
    private var cabinAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        kit = Kit(this)
        val root = kit.row().apply {
            setBackgroundResource(R.drawable.bg_root)
            setPadding(kit.dp(Kit.FOG_SAFE_DP + 24), kit.dp(28), kit.dp(40), kit.dp(28))
        }
        // Gauche : la température intérieure, en énorme
        val left = kit.column().apply { gravity = Gravity.CENTER }
        left.addView(kit.text(getString(R.string.dm_inside), 34f, R.color.text_title).apply { letterSpacing = 0.15f })
        inside = kit.text("—", 230f, light = true).apply { gravity = Gravity.CENTER; fontFeatureSettings = "tnum" }
        left.addView(inside, kit.lp(-2, -2))
        outside = kit.text("", 38f, R.color.text_sub)
        left.addView(outside, kit.lp(-2, -2, t = 4))
        root.addView(left, kit.lp(0, -1, 1f))
        // Droite : état de la clim, temps, message aux passants, batterie, arrêt
        val right = kit.column().apply { gravity = Gravity.CENTER_VERTICAL }
        state = kit.text("", 58f).apply { typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) }
        right.addView(state)
        time = kit.text("", 104f, light = true).apply { fontFeatureSettings = "tnum" }
        right.addView(time, kit.lp(-2, -2, t = 4))
        timeLabel = kit.text("", 30f, R.color.text_sub)
        right.addView(timeLabel, kit.lp(-2, -2))
        right.addView(kit.text(getString(R.string.dm_message), 32f).apply { maxLines = 3; setLineSpacing(0f, 1.15f) }, kit.lp(-1, -2, t = 24))
        val bottom = kit.row()
        battery = kit.text("", 34f)
        bottom.addView(battery, kit.lp(0, -2, 1f))
        bottom.addView(kit.toggleTile(getString(R.string.dm_hold_stop)) {}.apply {
            textSize = 24f
            setOnClickListener { android.widget.Toast.makeText(this@DogModeActivity, R.string.dm_hold_stop, android.widget.Toast.LENGTH_SHORT).show() }
            setOnLongClickListener { DogMode.stop("bouton de l'écran"); finish(); true }
        }, kit.lp(kit.dp(330), kit.dp(72)))
        right.addView(bottom, kit.lp(-1, -2, t = 24))
        root.addView(right, kit.lp(0, -1, 1.25f, l = 40))
        setContentView(root)
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
            val st = DogMode.status.value
            if (st.phase == DogMode.Phase.IDLE) { finish(); return }
            render(st)
            handler.postDelayed(this, 1000)
        }
    }

    private fun render(st: DogMode.Status) {
        val car = CarBridge.state.value
        val now = SystemClock.elapsedRealtime()
        // Température intérieure relue toutes les 10 s (lecture directe de la voiture)
        if (now - cabinAt > 10_000) { cabinAt = now; Thread { cabin = CarBridge.cabinTemp() }.start() }
        val t = cabin
        inside.text = t?.let { String.format(Locale.getDefault(), "%.0f°", it) } ?: "—"
        inside.setTextColor(kit.color(when {
            t == null -> R.color.text_sub
            t <= 22f -> R.color.accent_cold
            t <= 27f -> R.color.accent_energy
            t <= 32f -> R.color.accent_heat
            else -> R.color.accent_danger
        }))
        outside.text = car.outsideTemp?.let { getString(R.string.dm_outside, String.format(Locale.getDefault(), "%.0f°", it)) } ?: ""
        val on = car.hvacOn == true
        when (st.phase) {
            DogMode.Phase.ON -> {
                state.text = getString(if (on) R.string.dm_clim_on else R.string.dm_clim_off)
                state.setTextColor(kit.color(if (on) R.color.accent_cold else R.color.accent_heat))
                if (st.endAt > 0) { time.text = left(st.endAt - now); timeLabel.text = getString(R.string.dm_left) }
                else { time.text = left(now - st.startAt); timeLabel.text = getString(R.string.dm_since) }
                timeLabel.text = timeLabel.text.toString() + (car.driverTemp?.let { " · " + getString(R.string.dm_set, String.format(Locale.getDefault(), "%.1f°", it)) } ?: "")
            }
            DogMode.Phase.DONE, DogMode.Phase.BATTERY -> {
                state.text = getString(if (st.phase == DogMode.Phase.DONE) R.string.dm_state_done else R.string.dm_state_battery)
                state.setTextColor(kit.color(if (st.phase == DogMode.Phase.DONE) R.color.text_value else R.color.accent_danger))
                time.text = ""; timeLabel.text = ""
            }
            else -> {}
        }
        battery.text = car.batteryPercent?.let { getString(R.string.dm_battery, "$it %") } ?: ""
    }

    companion object {
        /** Durée : « 12:34 », ou « 1 h 05 » au-delà d'une heure. */
        fun left(ms: Long): String {
            val s = (ms.coerceAtLeast(0) + 999) / 1000
            return if (s >= 3600) String.format(Locale.getDefault(), "%d h %02d", s / 3600, s % 3600 / 60)
            else String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60)
        }
    }
}
