package app.mg4dash.climate

import android.app.Activity
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.mg4dash.R
import app.mg4dash.system.Prefs
import app.mg4dash.ui.Kit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fenêtre de réglage de la pré-climatisation (appui long sur le bouton marche de la clim, issue #26).
 * Dessinée par-dessus la page dans la même fenêtre : une boîte de dialogue ferait réapparaître les
 * barres système d'origine. Réglages → confirmation (partir avec la clé) → suivi du programme.
 */
object PreCoolPanel {
    private val TAG = Any()

    fun show(a: Activity) {
        val content = a.findViewById<ViewGroup>(android.R.id.content)
        content.findViewWithTag<View>(TAG)?.let { content.removeView(it) }
        val kit = Kit(a)
        val shade = FrameLayout(a).apply {
            tag = TAG
            setBackgroundColor(0xB3000000.toInt())
            isClickable = true   // la page dessous ne reçoit plus les touchers
        }
        val panel = kit.panel(a.getString(R.string.pc_title)).apply {
            setPadding(kit.dp(32), kit.dp(24), kit.dp(32), kit.dp(24))
            // Fond plein : l'accueil ne doit pas se voir à travers
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(kit.color(R.color.bg_bottom)); cornerRadius = a.resources.getDimension(R.dimen.radius_panel)
                setStroke(kit.dp(1), kit.color(R.color.panel_border))
            }
        }
        shade.addView(panel, FrameLayout.LayoutParams(kit.dp(1180), -2, Gravity.CENTER))
        content.addView(shade, FrameLayout.LayoutParams(-1, -1))
        val close = { content.removeView(shade) }
        if (PreCool.status.value.phase != PreCool.Phase.IDLE) follow(a, kit, panel, close) else settings(a, kit, panel, close)
    }

    private fun body(panel: LinearLayout): LinearLayout {
        while (panel.childCount > 1) panel.removeViewAt(1)
        return panel
    }

    private fun minutes(m: Int) = if (m >= 60) String.format(Locale.getDefault(), "%d h %02d", m / 60, m % 60) else "$m min"

    private fun settings(a: Activity, kit: Kit, panel: LinearLayout, close: () -> Unit) {
        val p = body(panel)
        val prefs = Prefs(a)
        val v = prefs.preCoolPlan
        // Libellé, pas, bornes, format
        data class Row(val label: Int, val step: Int, val min: Int, val max: Int, val fmt: (Int) -> String)
        val rows = listOf(
            Row(R.string.pc_lock_delay, 15, 15, 600) { if (it >= 60) "${it / 60} min ${if (it % 60 > 0) "${it % 60} s" else ""}".trim() else "$it s" },
            Row(R.string.pc_start_delay, 5, 0, 720) { minutes(it) },
            Row(R.string.pc_duration, 5, 5, 60) { "$it min" },
            Row(R.string.pc_runs, 1, 1, 6) { "$it" },
            Row(R.string.pc_gap, 5, 5, 240) { minutes(it) },
        )
        rows.forEachIndexed { i, r ->
            val line = kit.row()
            line.addView(kit.text(a.getString(r.label), 24f, R.color.text_sub), kit.lp(0, -2, 1f))
            val value = kit.text(r.fmt(v[i]), 30f).apply { gravity = Gravity.CENTER; fontFeatureSettings = "tnum" }
            val step = { d: Int -> v[i] = (v[i] + d * r.step).coerceIn(r.min, r.max); value.text = r.fmt(v[i]); prefs.preCoolPlan = v }
            line.addView(kit.roundButton(R.drawable.ic_minus, 60) { step(-1) })
            line.addView(value, kit.lp(kit.dp(190), -2))
            line.addView(kit.roundButton(R.drawable.ic_plus, 60) { step(1) })
            p.addView(line, kit.lp(-1, -2, t = 14))
        }
        p.addView(kit.text(a.getString(R.string.pc_note), 19f, R.color.text_title).apply { maxLines = 3 }, kit.lp(-1, -2, t = 18))
        val buttons = kit.row()
        buttons.addView(kit.toggleTile(a.getString(R.string.pc_close)) { close() }.apply { textSize = 22f }, kit.lp(0, kit.dp(64), 1f))
        buttons.addView(kit.toggleTile(a.getString(R.string.pc_activate)) { confirm(a, kit, panel, close) }
            .apply { textSize = 22f; isSelected = true }, kit.lp(0, kit.dp(64), 1f, l = 16))
        p.addView(buttons, kit.lp(-1, -2, t = 22))
    }

    private fun confirm(a: Activity, kit: Kit, panel: LinearLayout, close: () -> Unit) {
        val p = body(panel)
        p.addView(kit.text(a.getString(R.string.pc_confirm_title), 40f, R.color.accent_heat), kit.lp(-1, -2, t = 18))
        p.addView(kit.text(a.getString(R.string.pc_confirm_msg), 24f).apply { maxLines = 6; setLineSpacing(0f, 1.2f) }, kit.lp(-1, -2, t = 16))
        val buttons = kit.row()
        buttons.addView(kit.toggleTile(a.getString(R.string.cancel)) { settings(a, kit, panel, close) }.apply { textSize = 22f }, kit.lp(0, kit.dp(64), 1f))
        buttons.addView(kit.toggleTile(a.getString(R.string.pc_confirm_go)) {
            val v = Prefs(a).preCoolPlan
            PreCool.start(a, PreCool.Plan(v[0], v[1], v[2], v[3], v[4]))
            follow(a, kit, panel, close)
        }.apply { textSize = 22f; isSelected = true }, kit.lp(0, kit.dp(64), 1f, l = 16))
        p.addView(buttons, kit.lp(-1, -2, t = 26))
    }

    /** Suivi : heure de verrouillage, cycles prévus (à noter), état en direct, et bouton d'arrêt. */
    private fun follow(a: Activity, kit: Kit, panel: LinearLayout, close: () -> Unit) {
        val p = body(panel)
        val f = SimpleDateFormat("HH:mm", Locale.getDefault())
        val st0 = PreCool.status.value
        p.addView(kit.text(a.getString(R.string.pc_lock_at, f.format(Date(st0.lockClock))), 26f), kit.lp(-1, -2, t = 16))
        val runs = kit.text("", 26f, R.color.text_sub).apply { maxLines = 6; setLineSpacing(0f, 1.2f) }
        p.addView(runs, kit.lp(-1, -2, t = 10))
        val live = kit.text("", 34f, R.color.accent_cold)
        p.addView(live, kit.lp(-1, -2, t = 16))
        val buttons = kit.row()
        buttons.addView(kit.toggleTile(a.getString(R.string.pc_close)) { close() }.apply { textSize = 22f }, kit.lp(0, kit.dp(64), 1f))
        buttons.addView(kit.toggleTile(a.getString(R.string.pc_stop)) { PreCool.stop("bouton de la fenêtre"); close() }
            .apply { textSize = 22f; setTextColor(kit.color(R.color.accent_danger)) }, kit.lp(0, kit.dp(64), 1f, l = 16))
        p.addView(buttons, kit.lp(-1, -2, t = 22))
        val tick = object : Runnable {
            override fun run() {
                if (!panel.isAttachedToWindow) return
                val st = PreCool.status.value
                if (st.phase == PreCool.Phase.IDLE) { close(); return }
                runs.text = PreCoolActivity.runLines(a, st)
                val now = SystemClock.elapsedRealtime()
                live.text = when (st.phase) {
                    PreCool.Phase.ARMED -> a.getString(R.string.pc_lock_in) + "  " + PreCoolActivity.left(st.lockAt - now)
                    PreCool.Phase.WAITING -> a.getString(R.string.pc_clim_off) + " · " + PreCoolActivity.left((st.runs.getOrNull(st.run)?.get(0) ?: now) - now)
                    PreCool.Phase.RUNNING -> a.getString(R.string.pc_clim_on) + " · " + PreCoolActivity.left((st.runs.getOrNull(st.run)?.get(1) ?: now) - now)
                    PreCool.Phase.DONE -> a.getString(R.string.pc_state_done)
                    PreCool.Phase.BATTERY -> a.getString(R.string.pc_state_battery)
                    PreCool.Phase.FAILED -> a.getString(R.string.pc_state_failed)
                    else -> ""
                }
                panel.postDelayed(this, 1000)
            }
        }
        tick.run()
    }
}
