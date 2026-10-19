package app.mg4dash.climate

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import app.mg4dash.R
import app.mg4dash.system.Prefs
import app.mg4dash.ui.Kit

/**
 * Fenêtre du mode chien (appui long sur le bouton marche de la clim) : durée maximale, puis Activer.
 * Dessinée par-dessus la page dans la même fenêtre : une boîte de dialogue ferait réapparaître les
 * barres système d'origine. Si le mode tourne déjà, l'appui long rouvre directement son écran.
 */
object DogModePanel {
    private val TAG = Any()

    fun show(a: Activity) {
        if (DogMode.status.value.phase != DogMode.Phase.IDLE) { DogMode.show(a); return }
        val content = a.findViewById<ViewGroup>(android.R.id.content)
        content.findViewWithTag<View>(TAG)?.let { content.removeView(it) }
        val kit = Kit(a)
        val prefs = Prefs(a)
        val shade = FrameLayout(a).apply {
            tag = TAG
            setBackgroundColor(0xB3000000.toInt())
            isClickable = true   // la page dessous ne reçoit plus les touchers
        }
        val panel = kit.panel(a.getString(R.string.dm_title)).apply {
            setPadding(kit.dp(32), kit.dp(24), kit.dp(32), kit.dp(24))
            // Fond plein : l'accueil ne doit pas se voir à travers
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(kit.color(R.color.bg_bottom)); cornerRadius = a.resources.getDimension(R.dimen.radius_panel)
                setStroke(kit.dp(1), kit.color(R.color.panel_border))
            }
        }
        shade.addView(panel, FrameLayout.LayoutParams(kit.dp(1100), -2, Gravity.CENTER))
        content.addView(shade, FrameLayout.LayoutParams(-1, -1))
        val close = { content.removeView(shade) }

        // Durée maximale, parmi DogMode.DURATIONS (0 = illimitée)
        var idx = DogMode.DURATIONS.indexOf(prefs.dogModeMaxMin).coerceAtLeast(0)
        fun fmt(m: Int) = when {
            m == 0 -> a.getString(R.string.dm_unlimited)
            m >= 60 -> "${m / 60} h"
            else -> "$m min"
        }
        val line = kit.row()
        line.addView(kit.text(a.getString(R.string.dm_max), 26f, R.color.text_sub), kit.lp(0, -2, 1f))
        val value = kit.text(fmt(DogMode.DURATIONS[idx]), 30f).apply { gravity = Gravity.CENTER }
        val step = { d: Int ->
            idx = (idx + d).coerceIn(0, DogMode.DURATIONS.size - 1)
            value.text = fmt(DogMode.DURATIONS[idx]); prefs.dogModeMaxMin = DogMode.DURATIONS[idx]
        }
        line.addView(kit.roundButton(R.drawable.ic_minus, 60) { step(-1) })
        line.addView(value, kit.lp(kit.dp(220), -2))
        line.addView(kit.roundButton(R.drawable.ic_plus, 60) { step(1) })
        panel.addView(line, kit.lp(-1, -2, t = 18))
        panel.addView(kit.text(a.getString(R.string.dm_note), 21f, R.color.text_title).apply { maxLines = 4; setLineSpacing(0f, 1.15f) },
            kit.lp(-1, -2, t = 18))
        val buttons = kit.row()
        buttons.addView(kit.toggleTile(a.getString(R.string.dm_close)) { close() }.apply { textSize = 22f }, kit.lp(0, kit.dp(64), 1f))
        buttons.addView(kit.toggleTile(a.getString(R.string.dm_activate)) {
            close(); DogMode.start(a, DogMode.DURATIONS[idx])
        }.apply { textSize = 22f; isSelected = true }, kit.lp(0, kit.dp(64), 1f, l = 16))
        panel.addView(buttons, kit.lp(-1, -2, t = 24))
    }
}
