package app.mg4dash.ui

import android.app.Activity
import android.app.AlertDialog
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.mg4dash.R
import app.mg4dash.system.HeadUnitLoad
import app.mg4dash.system.ProcessStats
import java.util.Locale

/**
 * Détail de la charge de l'autoradio (#69), ouvert depuis la section « Autoradio » de la page Véhicule :
 * une ligne par appli ou service avec son processeur et sa mémoire, relus toutes les 2 s, triés par nom,
 * processeur ou mémoire (boutons en haut des colonnes ; un nouvel appui inverse l'ordre).
 * Fenêtre comme les autres : le changement de thème attend sa fermeture (voir Themes.popupOpen).
 */
object ProcessDialog {
    private const val BY_NAME = 0
    private const val BY_CPU = 1
    private const val BY_RAM = 2

    fun show(activity: Activity) {
        val kit = Kit(activity)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var sort = BY_CPU
        var descending = true
        var last: List<ProcessStats.Proc> = emptyList()

        val box = kit.column().apply { setPadding(kit.dp(24), kit.dp(8), kit.dp(24), kit.dp(8)) }
        val summary = kit.text("", 18f, R.color.text_sub)
        box.addView(summary, kit.lp(-1, -2))
        val head = kit.row()
        val list = kit.column()
        lateinit var buttons: List<TextView>

        fun label(i: Int) = activity.getString(listOf(R.string.ps_col_app, R.string.ps_col_cpu, R.string.ps_col_ram)[i]) +
            if (i == sort) (if (descending) "  ▼" else "  ▲") else ""

        fun render() {
            buttons.forEachIndexed { i, b -> b.isSelected = i == sort; b.text = label(i) }
            val sorted = when (sort) {
                BY_NAME -> last.sortedBy { it.label.lowercase(Locale.getDefault()) }
                BY_CPU -> last.sortedBy { it.cpu ?: -1f }
                else -> last.sortedBy { it.ramMb ?: -1 }
            }
            val rows = if (descending) sorted.reversed() else sorted
            list.removeAllViews()
            rows.forEachIndexed { i, p ->
                if (i > 0) list.addView(kit.divider(), kit.lp(-1, 1))
                val r = kit.row().apply { setPadding(0, kit.dp(9), 0, kit.dp(9)) }
                val icon = try { activity.packageManager.getApplicationIcon(p.packageName) } catch (_: Exception) { null }
                r.addView(ImageView(activity).apply { if (icon != null) setImageDrawable(icon) else setImageResource(R.drawable.ic_app) }, kit.lp(kit.dp(30), kit.dp(30), r = 14))
                val name = kit.column()
                name.addView(kit.text(p.label, 19f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                if (p.service) name.addView(kit.text(activity.getString(R.string.ps_service), 14f, R.color.text_sub))
                r.addView(name, kit.lp(0, -2, 1f))
                // « ≈ » : moyenne récente du système (appli que MG4Dash ne peut pas lire en direct), en gris
                r.addView(kit.text(p.cpu?.let { (if (p.cpuAverage) "≈ " else "") + String.format(Locale.getDefault(), if (it < 10f) "%.1f %%" else "%.0f %%", it) } ?: "—", 19f,
                    if (p.cpuAverage) R.color.text_sub else R.color.text_value)
                    .apply { gravity = Gravity.END }, kit.lp(kit.dp(150), -2, l = 10))
                r.addView(kit.text(p.ramMb?.let { "$it ${activity.getString(R.string.unit_mb)}" } ?: "—", 19f)
                    .apply { gravity = Gravity.END }, kit.lp(kit.dp(170), -2, l = 10, r = 16))
                list.addView(r, kit.lp(-1, -2))
            }
        }

        fun sortButton(i: Int, lp: LinearLayout.LayoutParams) = kit.toggleTile(label(i)) {
            if (sort == i) descending = !descending else { sort = i; descending = i != BY_NAME }
            render()
        }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = if (i == BY_NAME) Gravity.CENTER_VERTICAL or Gravity.START else Gravity.CENTER
            setPadding(kit.dp(16), 0, kit.dp(16), 0)
        }.also { head.addView(it, lp) }
        buttons = listOf(
            sortButton(BY_NAME, kit.lp(0, kit.dp(44), 1f)),
            sortButton(BY_CPU, kit.lp(kit.dp(150), kit.dp(44), l = 10)),
            sortButton(BY_RAM, kit.lp(kit.dp(170), kit.dp(44), l = 10)),
        )
        box.addView(head, kit.lp(-1, -2, t = 14))
        val scroll = ScrollView(activity).apply { addView(list) }
        box.addView(scroll, kit.lp(-1, kit.dp(330), t = 4))
        box.addView(kit.text(activity.getString(R.string.ps_note), 14f, R.color.text_sub), kit.lp(-1, -2, t = 6))

        var open = true
        val tick = object : Runnable {
            override fun run() {
                if (!open) return
                Thread {
                    val procs = try { ProcessStats.snapshot(activity) } catch (e: Exception) { app.mg4dash.system.Log.w("Processes", "${e.message}"); emptyList() }
                    val cpu = HeadUnitLoad.cpuPercent(); val mem = HeadUnitLoad.memoryMb()
                    handler.post {
                        if (!open) return@post
                        last = procs
                        summary.text = activity.getString(R.string.ps_summary, cpu?.let { "$it %" } ?: "—",
                            mem?.let { (u, t) -> String.format(Locale.getDefault(), "%,d / %,d %s", u, t, activity.getString(R.string.unit_mb)) } ?: "—")
                        render()
                    }
                }.start()
                handler.postDelayed(this, 2_000)
            }
        }
        val dlg = AlertDialog.Builder(activity)
            // Titre dessiné par MG4Dash : celui du système sortait très grand et grisé sur la voiture (#69)
            .setCustomTitle(kit.text(activity.getString(R.string.ps_title), 22f).apply {
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setPadding(kit.dp(24), kit.dp(20), kit.dp(24), kit.dp(4))
            })
            .setView(box)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener { open = false; handler.removeCallbacks(tick) }
            .create()
        // Plein écran gardé : fenêtre affichée sans prendre le focus, mêmes réglages d'écran que la page,
        // puis focus rendu (sinon les barres SAIC réapparaissent à l'ouverture)
        dlg.window?.setFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        dlg.show()
        dlg.window?.decorView?.systemUiVisibility = activity.window.decorView.systemUiVisibility
        dlg.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        tick.run()
    }
}
