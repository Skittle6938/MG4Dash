package app.mg4dash.ui

import android.app.Activity
import android.graphics.drawable.Drawable
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
 * Detail of the head unit's load (#69), opened from the "Head unit" section of the Vehicle page:
 * one line per app or service with its CPU and memory, reread every 3 s, sorted by name,
 * CPU or memory (buttons at the top of the columns; another press reverses the order).
 * A window like the others: the theme change waits for it to close (see Themes.popupOpen).
 *
 * Light by design (24/11, measured on the car): the list once rebuilt every line and reloaded every
 * icon every 2 s, ~150 ms on the main thread, about half of what MG4Dash showed. Now each line is made
 * once and only its numbers change; names and icons are kept ([ProcessStats] and [Lines]).
 */
object ProcessDialog {
    const val BY_NAME = 0
    const val BY_CPU = 1
    const val BY_RAM = 2
    const val REFRESH_MS = 3_000L

    /** The lines of the list, made once per process and reused; only their numbers and order change. */
    class Lines(private val activity: Activity, private val kit: Kit, val list: LinearLayout) {
        private class Line(val view: LinearLayout, val cpu: TextView, val ram: TextView)
        private val lines = HashMap<Int, Line>()
        private val icons = HashMap<String, Drawable?>()
        private var shown: List<Int> = emptyList()
        /** Lines made so far (tests: a refresh with the same processes makes none). */
        var made = 0; private set

        private fun icon(pkg: String) = icons.getOrPut(pkg) { try { activity.packageManager.getApplicationIcon(pkg) } catch (_: Exception) { null } }

        private fun make(p: ProcessStats.Proc): Line {
            made++
            val r = kit.row().apply { setPadding(0, kit.dp(9), 0, kit.dp(9)) }
            r.addView(ImageView(activity).apply { icon(p.packageName)?.let { setImageDrawable(it) } ?: setImageResource(R.drawable.ic_app) }, kit.lp(kit.dp(30), kit.dp(30), r = 14))
            val name = kit.column()
            name.addView(kit.text(p.label, 19f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            // MG4Dash's own line: its number includes this list's refresh
            val sub = when {
                p.packageName == activity.packageName && !p.service -> activity.getString(R.string.ps_self)
                p.service -> activity.getString(R.string.ps_service)
                else -> null
            }
            if (sub != null) name.addView(kit.text(sub, 14f, R.color.text_sub))
            r.addView(name, kit.lp(0, -2, 1f))
            val cpu = kit.text("", 19f).apply { gravity = Gravity.END }
            r.addView(cpu, kit.lp(kit.dp(150), -2, l = 10))
            val ram = kit.text("", 19f).apply { gravity = Gravity.END }
            r.addView(ram, kit.lp(kit.dp(170), -2, l = 10, r = 16))
            return Line(r, cpu, ram)
        }

        fun update(procs: List<ProcessStats.Proc>, sort: Int, descending: Boolean) {
            val sorted = when (sort) {
                BY_NAME -> procs.sortedBy { it.label.lowercase(Locale.getDefault()) }
                BY_CPU -> procs.sortedBy { it.cpu ?: -1f }
                else -> procs.sortedBy { it.ramMb ?: -1 }
            }.let { if (descending) it.reversed() else it }
            for (p in sorted) {
                val l = lines.getOrPut(p.pid) { make(p) }
                // "≈": the system's recent average (app MG4Dash cannot read live), in grey
                l.cpu.text = p.cpu?.let { (if (p.cpuAverage) "≈ " else "") + String.format(Locale.getDefault(), if (it < 10f) "%.1f %%" else "%.0f %%", it) } ?: "—"
                l.cpu.setTextColor(Themes.color(activity, if (p.cpuAverage) R.color.text_sub else R.color.text_value))
                l.ram.text = p.ramMb?.let { "$it ${activity.getString(R.string.unit_mb)}" } ?: "—"
            }
            lines.keys.retainAll(sorted.map { it.pid }.toSet())
            val order = sorted.map { it.pid }
            if (order == shown) return
            // The order changed: the same lines, placed again
            shown = order
            list.removeAllViews()
            order.forEachIndexed { i, pid ->
                if (i > 0) list.addView(kit.divider(), kit.lp(-1, 1))
                list.addView(lines.getValue(pid).view, kit.lp(-1, -2))
            }
        }
    }

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
        val lines = Lines(activity, kit, kit.column())
        lateinit var buttons: List<TextView>

        fun label(i: Int) = activity.getString(listOf(R.string.ps_col_app, R.string.ps_col_cpu, R.string.ps_col_ram)[i]) +
            if (i == sort) (if (descending) "  ▼" else "  ▲") else ""

        fun render() {
            buttons.forEachIndexed { i, b -> b.isSelected = i == sort; b.text = label(i) }
            lines.update(last, sort, descending)
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
        val scroll = ScrollView(activity).apply { addView(lines.list) }
        box.addView(scroll, kit.lp(-1, kit.dp(330), t = 4))
        box.addView(kit.text(activity.getString(R.string.ps_note), 14f, R.color.text_sub), kit.lp(-1, -2, t = 6))

        var open = true
        val tick = object : Runnable {
            override fun run() {
                if (!open) return
                // Everything read off the main thread (processes, memory, the system's averages); only the numbers are shown on it
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
                handler.postDelayed(this, REFRESH_MS)
            }
        }
        // The settings' window (24/11): title, round close button, a touch outside closes it; no OK
        Windows.show(activity, activity.getString(R.string.ps_title), box, widthDp = 1300) { open = false; handler.removeCallbacks(tick) }
        tick.run()
    }
}
