package app.mg4dash.pages

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import app.mg4dash.R
import app.mg4dash.car.VehicleState
import app.mg4dash.energy.ConsumptionMap
import app.mg4dash.ui.PageActivity
import app.mg4dash.ui.Themes
import java.util.Locale

/**
 * Carte de consommation (en test) : grille vitesse moyenne × température extérieure, couleur = kWh/100 km
 * de roulage, opacité = fiabilité (km mesurés). Un toucher sur une case en donne le détail.
 */
class ConsumptionMapActivity : PageActivity() {
    override val pageTitle get() = getString(R.string.cm_title)
    override val page = app.mg4dash.ui.Dock.Page.ENERGY

    private lateinit var grid: MapView
    private lateinit var detail: TextView
    private lateinit var summary: TextView

    override fun buildBody() {
        val left = kit.panel(getString(R.string.cm_grid_title))
        grid = MapView(this) { s, t -> showCell(s, t) }
        left.addView(grid, kit.lp(-1, 0, 1f, t = 8))
        body.addView(left, kit.lp(0, -1, 2.2f, r = 6))

        val right = kit.panel(getString(R.string.cm_about))
        summary = kit.text("", 22f).apply { maxLines = 3 }
        right.addView(summary, kit.lp(-1, -2, t = 8))
        right.addView(kit.text(getString(R.string.cm_legend), 19f, R.color.text_sub).apply { maxLines = 6; setLineSpacing(0f, 1.15f) }, kit.lp(-1, -2, t = 10))
        // Simulation « avec la clim » (affichage seulement, rien n'est envoyé à DiAuto) : la puissance moyenne
        // de la clim apprise par MG4Dash, ramenée aux km de chaque vitesse
        val climW = app.mg4dash.system.Prefs(this).raw.getFloat("em_clim_any", 1000f)
        val sim = kit.toggleTile(getString(R.string.cm_sim_clim, Math.round(climW))) {}
        sim.setOnClickListener { sim.isSelected = !sim.isSelected; grid.extraW = if (sim.isSelected) climW else 0f; selected?.let { (a, b) -> showCell(a, b) } }
        sim.textSize = 20f
        right.addView(sim, kit.lp(-1, kit.dp(60), t = 12))
        right.addView(kit.divider(), kit.lp(-1, 1, t = 12))
        detail = kit.text(getString(R.string.cm_tap), 22f, R.color.text_value).apply { maxLines = 5; setLineSpacing(0f, 1.15f) }
        right.addView(detail, kit.lp(-1, -2, t = 12))
        right.addView(View(this), kit.lp(-1, 0, 1f))
        val row = kit.row()
        row.addView(kit.toggleTile(getString(R.string.cm_export)) { export() }.apply { textSize = 20f }, kit.lp(0, kit.dp(64), 1f, r = 6))
        row.addView(kit.toggleTile(getString(R.string.cm_import)) { import() }.apply { textSize = 20f }, kit.lp(0, kit.dp(64), 1f, l = 6, r = 6))
        lateinit var clear: TextView
        clear = kit.toggleTile(getString(R.string.cm_clear)) {
            app.mg4dash.ui.SourceMenu.confirm(this, clear, getString(R.string.cm_clear_confirm), getString(R.string.cm_clear)) {
                ConsumptionMap.clear(this); refresh()
            }
        }.apply { textSize = 20f }
        row.addView(clear, kit.lp(0, kit.dp(64), 1f, l = 6))
        right.addView(row, kit.lp(-1, -2))
        right.addView(kit.text(getString(R.string.cm_test_note), 17f, R.color.accent_heat).apply { maxLines = 2 }, kit.lp(-1, -2, t = 8))
        body.addView(right, kit.lp(0, -1, 1f, l = 6))
        refresh()
    }

    private fun refresh() {
        val t = ConsumptionMap.table(this)
        val cells = t.flatten()
        val km = cells.sumOf { it.km.toDouble() }.toFloat()
        val filled = cells.count { it.n > 0 }
        summary.text = getString(R.string.cm_summary, Math.round(km), filled, ConsumptionMap.SPEED_BINS * ConsumptionMap.TEMP_BINS)
        grid.table = t
    }

    private var selected: Pair<Int, Int>? = null

    /** Racine de la clé USB branchée, ou null. */
    private fun usb(): java.io.File? {
        val sm = getSystemService(STORAGE_SERVICE) as android.os.storage.StorageManager
        return sm.storageVolumes.filter { it.isRemovable && it.state == android.os.Environment.MEDIA_MOUNTED }
            .firstNotNullOfOrNull { v -> try { v.javaClass.getMethod("getPathFile").invoke(v) as? java.io.File } catch (_: Throwable) { null } }
    }

    /** Export sur la clé : mg4dash_consumption_map.json à la racine, sinon dans le dossier de MG4Dash sur la clé. */
    private fun export() {
        val root = usb() ?: run { app.mg4dash.ui.Toasts.show(this, R.string.st_copy_sysui_nousb, long = true); return }
        val json = ConsumptionMap.toJson(ConsumptionMap.table(this))
        val targets = listOfNotNull(root, getExternalFilesDirs(null).firstOrNull { it != null && it.absolutePath.startsWith(root.absolutePath) })
        val done = targets.firstNotNullOfOrNull { dir ->
            try { java.io.File(dir, EXPORT_NAME).also { it.writeText(json) } } catch (_: Exception) { null }
        }
        app.mg4dash.ui.Toasts.show(this, if (done != null) getString(R.string.cm_exported, done.absolutePath.removePrefix(root.absolutePath).trimStart('/')) else getString(R.string.st_copy_sysui_fail), long = true)
    }

    /** Import depuis la clé (même nom de fichier, racine ou dossier de MG4Dash) : remplace la carte actuelle. */
    private fun import() {
        val root = usb() ?: run { app.mg4dash.ui.Toasts.show(this, R.string.st_copy_sysui_nousb, long = true); return }
        val f = listOfNotNull(root, getExternalFilesDirs(null).firstOrNull { it != null && it.absolutePath.startsWith(root.absolutePath) })
            .map { java.io.File(it, EXPORT_NAME) }.firstOrNull { it.canRead() }
            ?: run { app.mg4dash.ui.Toasts.show(this, getString(R.string.cm_import_none, EXPORT_NAME), long = true); return }
        try {
            val t = ConsumptionMap.fromJson(f.readText())
            ConsumptionMap.replace(this, t)
            refresh()
            app.mg4dash.ui.Toasts.show(this, R.string.cm_imported, long = true)
        } catch (e: Exception) { app.mg4dash.ui.Toasts.show(this, R.string.st_copy_sysui_fail, long = true) }
    }

    private fun showCell(s: Int, tb: Int) {
        selected = s to tb
        val c = ConsumptionMap.table(this)[s][tb]
        val speed = "${s * ConsumptionMap.SPEED_STEP}–${(s + 1) * ConsumptionMap.SPEED_STEP} km/h"
        val tMin = ConsumptionMap.TEMP_MIN + tb * ConsumptionMap.TEMP_STEP
        val temp = "$tMin…${tMin + ConsumptionMap.TEMP_STEP} °C"
        detail.text = if (c.n == 0) getString(R.string.cm_cell_empty, speed, temp)
        else getString(R.string.cm_cell, speed, temp, String.format(Locale.getDefault(), "%.1f", (c.per100 ?: 0f) + grid.extra(s)),
            String.format(Locale.getDefault(), "%.1f", c.spread ?: 0f), Math.round(c.km), c.n) +
            (if (grid.extraW > 0f) "\n" + getString(R.string.cm_sim_note) else "")
    }

    override fun render(s: VehicleState) {}

    private companion object {
        const val EXPORT_NAME = "mg4dash_consumption_map.json"
    }

    /** Grille : colonnes = vitesse, lignes = température (froid en bas). */
    class MapView(context: Context, val onPick: (Int, Int) -> Unit) : View(context) {
        var table: Array<Array<ConsumptionMap.Cell>>? = null
            set(v) { field = v; invalidate() }
        /** Puissance ajoutée pour la simulation (W) : clim, affichage seulement. */
        var extraW = 0f
            set(v) { field = v; invalidate() }
        /** kWh/100 km ajoutés à la vitesse de la colonne [s] : puissance ÷ vitesse (milieu de la case). */
        fun extra(s: Int): Float = if (extraW <= 0f) 0f else extraW / 1000f / ((s + 0.5f) * ConsumptionMap.SPEED_STEP) * 100f
        private var picked: Pair<Int, Int>? = null
        private val d = resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = Themes.color(context, R.color.accent_action) }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 17f * d }
        private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 17f * d; color = Themes.color(context, R.color.text_sub) }
        private val empty = Themes.color(context, R.color.gauge_off)
        private val r = RectF()
        private val left get() = 64 * d
        private val bottom get() = height - 32 * d

        /** Vert (sobre, ≤ 12) → jaune → rouge (≥ 26 kWh/100 km). */
        private fun colorFor(v: Float): Int {
            val f = ((v - 12f) / 14f).coerceIn(0f, 1f)
            val k = if (f < 0.5f) f * 2 else (f - 0.5f) * 2
            val a = if (f < 0.5f) Triple(0x22, 0xC5, 0x5E) else Triple(0xEA, 0xB3, 0x08)
            val b = if (f < 0.5f) Triple(0xEA, 0xB3, 0x08) else Triple(0xEF, 0x44, 0x44)
            fun mix(x: Int, y: Int) = (x + (y - x) * k).toInt()
            return android.graphics.Color.rgb(mix(a.first, b.first), mix(a.second, b.second), mix(a.third, b.third))
        }

        override fun onDraw(canvas: Canvas) {
            val t = table ?: return
            val cw = (width - left) / ConsumptionMap.SPEED_BINS
            val ch = bottom / ConsumptionMap.TEMP_BINS
            for (s in 0 until ConsumptionMap.SPEED_BINS) for (tb in 0 until ConsumptionMap.TEMP_BINS) {
                val c = t[s][tb]
                val x = left + s * cw; val y = bottom - (tb + 1) * ch
                r.set(x + 1.5f * d, y + 1.5f * d, x + cw - 1.5f * d, y + ch - 1.5f * d)
                val v = c.per100?.let { it + extra(s) }
                if (v == null) { fill.color = empty; fill.alpha = 255 }
                else {
                    fill.color = colorFor(v)
                    // Fiabilité : opacité selon les km mesurés (plein dès 20 km)
                    fill.alpha = (70 + 185 * (c.km / 20f).coerceIn(0f, 1f)).toInt()
                }
                canvas.drawRoundRect(r, 4 * d, 4 * d, fill)
                if (v != null) {
                    text.color = if (fill.alpha > 160) 0xFF101010.toInt() else Themes.color(context, R.color.text_value)
                    canvas.drawText(String.format(Locale.getDefault(), "%.0f", v), r.centerX(), r.centerY() + text.textSize / 3, text)
                }
                if (picked == s to tb) canvas.drawRoundRect(r, 4 * d, 4 * d, stroke)
            }
            // Axes : vitesse en bas (tous les 20 km/h), température à gauche (tous les 10 °C)
            axis.textAlign = Paint.Align.CENTER
            for (s in 0..ConsumptionMap.SPEED_BINS step 2) canvas.drawText("${s * ConsumptionMap.SPEED_STEP}", left + s * cw, height - 6 * d, axis)
            axis.textAlign = Paint.Align.RIGHT
            for (tb in 0 until ConsumptionMap.TEMP_BINS step 2) canvas.drawText("${ConsumptionMap.TEMP_MIN + tb * ConsumptionMap.TEMP_STEP}°", left - 8 * d, bottom - tb * ch + axis.textSize / 3, axis)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action != MotionEvent.ACTION_UP) return true
            val cw = (width - left) / ConsumptionMap.SPEED_BINS
            val ch = bottom / ConsumptionMap.TEMP_BINS
            val s = ((e.x - left) / cw).toInt(); val tb = ((bottom - e.y) / ch).toInt()
            if (s in 0 until ConsumptionMap.SPEED_BINS && tb in 0 until ConsumptionMap.TEMP_BINS) { picked = s to tb; invalidate(); onPick(s, tb) }
            return true
        }
    }
}
