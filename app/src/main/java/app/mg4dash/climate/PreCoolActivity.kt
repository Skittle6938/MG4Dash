package app.mg4dash.climate

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.mg4dash.R
import app.mg4dash.car.CarBridge
import app.mg4dash.ui.Fullscreen
import app.mg4dash.ui.Kit
import app.mg4dash.ui.Themes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Écran de veille de la pré-climatisation (issue #26) : très gros, lisible depuis l'extérieur à travers
 * la vitre. Température intérieure avec un code couleur, état de la clim, compte à rebours, cycles
 * prévus, batterie. L'écran reste allumé tant que le programme tourne ; il se ferme quand le conducteur
 * revient (portes déverrouillées) ou qu'on maintient le bouton d'arrêt.
 */
class PreCoolActivity : AppCompatActivity() {
    private lateinit var kit: Kit
    private lateinit var inside: TextView
    private lateinit var outside: TextView
    private lateinit var state: TextView
    private lateinit var countdown: TextView
    private lateinit var setTemp: TextView
    private lateinit var runs: TextView
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
        left.addView(kit.text(getString(R.string.pc_inside), 34f, R.color.text_title).apply { letterSpacing = 0.15f })
        inside = kit.text("—", 230f, light = true).apply { gravity = Gravity.CENTER; fontFeatureSettings = "tnum" }
        left.addView(inside, kit.lp(-2, -2))
        outside = kit.text("", 38f, R.color.text_sub)
        left.addView(outside, kit.lp(-2, -2, t = 4))
        root.addView(left, kit.lp(0, -1, 1f))
        // Droite : état, compte à rebours, cycles, batterie, arrêt
        val right = kit.column().apply { gravity = Gravity.CENTER_VERTICAL }
        state = kit.text("", 58f).apply { typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) }
        right.addView(state)
        countdown = kit.text("", 120f, light = true).apply { fontFeatureSettings = "tnum" }
        right.addView(countdown, kit.lp(-2, -2, t = 4))
        setTemp = kit.text("", 34f, R.color.text_sub)
        right.addView(setTemp, kit.lp(-2, -2, t = 4))
        runs = kit.text("", 28f, R.color.text_sub).apply { maxLines = 6; setLineSpacing(0f, 1.15f) }
        right.addView(runs, kit.lp(-2, -2, t = 18))
        val bottom = kit.row()
        battery = kit.text("", 34f)
        bottom.addView(battery, kit.lp(0, -2, 1f))
        bottom.addView(kit.toggleTile(getString(R.string.pc_hold_stop)) {}.apply {
            textSize = 24f
            setOnClickListener { android.widget.Toast.makeText(this@PreCoolActivity, R.string.pc_hold_stop, android.widget.Toast.LENGTH_SHORT).show() }
            setOnLongClickListener { PreCool.stop("bouton de l'écran de veille"); finish(); true }
        }, kit.lp(kit.dp(330), kit.dp(72)))
        right.addView(bottom, kit.lp(-1, -2, t = 22))
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
            val st = PreCool.status.value
            if (st.phase == PreCool.Phase.IDLE) { finish(); return }
            render(st)
            handler.postDelayed(this, 1000)
        }
    }

    private fun render(st: PreCool.Status) {
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
        outside.text = car.outsideTemp?.let { getString(R.string.pc_outside, String.format(Locale.getDefault(), "%.0f°", it)) } ?: ""
        val on = car.hvacOn == true
        val (label, color, until) = when (st.phase) {
            PreCool.Phase.ARMED -> Triple(getString(R.string.pc_lock_in), R.color.text_value, st.lockAt)
            PreCool.Phase.WAITING -> Triple(getString(R.string.pc_clim_off), R.color.text_value, st.runs.getOrNull(st.run)?.get(0))
            PreCool.Phase.RUNNING -> Triple(getString(if (on) R.string.pc_clim_on else R.string.pc_clim_refused),
                if (on) R.color.accent_cold else R.color.accent_danger, st.runs.getOrNull(st.run)?.get(1))
            PreCool.Phase.DONE -> Triple(getString(R.string.pc_state_done), R.color.accent_energy, null)
            PreCool.Phase.BATTERY -> Triple(getString(R.string.pc_state_battery), R.color.accent_danger, null)
            PreCool.Phase.FAILED -> Triple(getString(R.string.pc_state_failed), R.color.accent_danger, null)
            else -> Triple("", R.color.text_value, null)
        }
        state.text = label; state.setTextColor(kit.color(color))
        countdown.text = until?.let { left(it - now) } ?: ""
        countdown.setTextColor(kit.color(if (st.phase == PreCool.Phase.RUNNING && on) R.color.accent_cold else R.color.text_value))
        setTemp.text = when (st.phase) {
            PreCool.Phase.WAITING -> getString(R.string.pc_until_start)
            PreCool.Phase.RUNNING -> car.driverTemp?.let { getString(R.string.pc_set, String.format(Locale.getDefault(), "%.1f°", it)) } ?: getString(R.string.pc_until_end)
            else -> ""
        }
        runs.text = runLines(this, st)
        battery.text = car.batteryPercent?.let { getString(R.string.pc_battery, "$it %") } ?: ""
    }

    companion object {
        /** Durée restante : « 12:34 », ou « 1 h 05 » au-delà d'une heure. */
        fun left(ms: Long): String {
            val s = (ms.coerceAtLeast(0) + 999) / 1000
            return if (s >= 3600) String.format(Locale.getDefault(), "%d h %02d", s / 3600, s % 3600 / 60)
            else String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60)
        }

        /** Les cycles prévus, en heures murales, celui en cours repéré par une flèche. */
        fun runLines(c: android.content.Context, st: PreCool.Status): String {
            val f = SimpleDateFormat("HH:mm", Locale.getDefault())
            return st.clock.mapIndexed { i, (a, b) ->
                val mark = when {
                    st.phase == PreCool.Phase.RUNNING && i == st.run -> "▶ "
                    st.finished || (st.run > i) -> "✓ "
                    else -> "    "
                }
                mark + c.getString(R.string.pc_run_line, i + 1, f.format(Date(a)), f.format(Date(b)))
            }.joinToString("\n")
        }
    }
}
