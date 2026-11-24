package app.mg4dash.chrono

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.mg4dash.R
import app.mg4dash.cards.CardPrefs
import app.mg4dash.ui.HomeSlots
import app.mg4dash.ui.Rows
import app.mg4dash.ui.SwitchView
import app.mg4dash.ui.Themes
import app.mg4dash.ui.Windows

/**
 * The chrono's long-press window: the all-time records and the last runs on the left, the options on the right; a
 * run touched opens its detail (power against speed, every condition, delete). Built from [Rows] like the others.
 * Building it starts nothing: it only reads what is kept.
 */
class ChronoWindow(private val a: Activity) {
    private val k = ChronoDraw(a)
    private val d = k.d
    private fun dp(v: Int) = (v * d).toInt()
    private fun col(res: Int) = Themes.color(a, res)
    private val prefs = CardPrefs(a, HomeSlots.Card.CHRONO)
    private val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }

    fun view(): View { ChronoEngine.load(a); main(); return root }

    private fun tv(text: CharSequence, sp: Float, color: Int = col(R.color.text_value), face: Typeface? = null, gravity: Int = Gravity.START) = TextView(a).apply {
        this.text = text; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color); this.gravity = gravity or Gravity.CENTER_VERTICAL
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; face?.let { typeface = it }
    }

    // ── Main page ──

    private fun main() {
        root.removeAllViews()
        val h = ChronoEngine.history.value
        val cols = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        val left = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val right = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        cols.addView(left, LinearLayout.LayoutParams(0, -2, 1.45f).apply { rightMargin = dp(14) })
        cols.addView(right, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(14) })
        root.addView(cols, LinearLayout.LayoutParams(-1, -2))

        // Records
        left.addView(Rows.header(a, a.getString(R.string.ch_records)))
        val tiles = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(ChronoHistory.Rec.FULL to k.label(ChronoMath.Kind.STANDING), ChronoHistory.Rec.SPLIT to k.label(ChronoMath.Kind.STANDING, true),
            ChronoHistory.Rec.ROLLING to k.label(ChronoMath.Kind.ROLLING)).forEachIndexed { i, (r, label) ->
            val run = h.record(r, k.mph)
            val t = run?.let { h.time(it, r) }
            val tile = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL; background = Rows.groupBackground(a); setPadding(dp(20), dp(14), dp(16), dp(14))
                // The press feedback over the tile's own background
                if (run != null) { Rows.touchable(this); foreground = background; background = Rows.groupBackground(a); setOnClickListener { detail(run) } }
            }
            tile.addView(tv(label.uppercase(), 13f, col(R.color.text_title), Typeface.create("sans-serif-medium", Typeface.NORMAL)).apply { letterSpacing = 0.1f })
            tile.addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
                addView(TrophyView(a, run != null), LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(8); bottomMargin = dp(8) })
                addView(tv(t?.let { k.time(it) } ?: "—", 40f, face = Themes.valueFont(a)))
                if (t != null) addView(tv(" s", 18f, col(R.color.text_sub)).apply { setPadding(0, 0, 0, dp(6)) })
            })
            tile.addView(tv(run?.let { "${k.date(it.at)} · " + listOfNotNull(it.soc?.let { s -> "$s %" }, it.outside?.let { o -> k.temp(o) }, it.climb?.let { c -> k.climb(c) }).joinToString(" · ") }
                ?: a.getString(R.string.ch_no_run), 14f, col(R.color.text_sub)))
            tiles.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(12) })
        }
        left.addView(tiles, LinearLayout.LayoutParams(-1, -2))

        // Last runs
        left.addView(Rows.header(a, a.getString(R.string.ch_last_runs)))
        val table = Rows.group(a)
        val runs = h.runs.filter { it.mph == k.mph }
        if (runs.isEmpty()) table.addView(tv(a.getString(R.string.ch_no_run), 18f, col(R.color.text_sub)).apply { setPadding(0, dp(24), 0, dp(24)) })
        else {
            table.addView(line(listOf(a.getString(R.string.ch_col_date), k.label(ChronoMath.Kind.STANDING, true).substringBefore(' '), k.label(ChronoMath.Kind.STANDING).substringBefore(' '),
                a.getString(R.string.ch_col_gap), a.getString(R.string.ch_battery), a.getString(R.string.ch_outside), a.getString(R.string.ch_climb), "kW", ""), header = true), LinearLayout.LayoutParams(-1, dp(44)))
            for (run in runs) {
                val rec = h.record(if (run.kind == ChronoMath.Kind.STANDING) ChronoHistory.Rec.FULL else ChronoHistory.Rec.ROLLING, k.mph)
                val gap = when {
                    !ChronoHistory.counts(run) -> SLOPE
                    rec == null -> ""
                    rec.at == run.at -> RECORD
                    else -> k.signed((run.full - rec.full).toDouble())
                }
                val split = if (run.kind == ChronoMath.Kind.ROLLING) k.label(run.kind).substringBefore(' ') else run.split?.let { k.time(it) } ?: "—"
                val row = line(listOf(k.date(run.at), split, k.time(run.full), gap, run.soc?.let { "$it %" } ?: "—", k.temp(run.outside), k.climb(run.climb),
                    run.maxKw?.let { "${Math.round(it)}" } ?: "—", "›"), header = false)
                row.tag = Rows.TAG_ROW; Rows.touchable(row); row.setOnClickListener { detail(run) }
                Rows.add(table, row, LinearLayout.LayoutParams(-1, dp(60)))
            }
        }
        left.addView(table, LinearLayout.LayoutParams(-1, -2))

        // Options
        right.addView(Rows.header(a, a.getString(R.string.ch_during)))
        val during = Rows.group(a)
        toggle(during, a.getString(R.string.ch_opt_sport), a.getString(R.string.ch_opt_sport_sub), "sport")
        toggle(during, a.getString(R.string.ch_opt_climate), a.getString(R.string.ch_opt_climate_sub), "climate")
        toggle(during, a.getString(R.string.ch_opt_fullscreen), a.getString(R.string.ch_opt_fullscreen_sub), "fullscreen")
        val t = ChronoMath.targets(ChronoMath.Kind.STANDING, k.mph)
        toggle(during, a.getString(R.string.ch_opt_beep, "${k.speed(t.split!!)}", "${k.speed(t.to)}"), null, "beep")
        right.addView(during, LinearLayout.LayoutParams(-1, -2))
        right.addView(Rows.header(a, a.getString(R.string.ch_opt_measure)))
        val measure = Rows.group(a)
        toggle(measure, a.getString(R.string.ch_opt_gps), a.getString(R.string.ch_opt_gps_sub), "gps")
        val r = ChronoMath.targets(ChronoMath.Kind.ROLLING, k.mph)
        toggle(measure, a.getString(R.string.ch_opt_rolling, k.label(ChronoMath.Kind.ROLLING)), a.getString(R.string.ch_opt_rolling_sub, "${k.speed(r.from)} ${k.unit}"), "rolling")
        right.addView(measure, LinearLayout.LayoutParams(-1, -2))
        val erase = Rows.group(a)
        Rows.add(erase, Rows.row(a, a.getString(R.string.ch_clear), a.getString(R.string.ch_clear_sub),
            Rows.actionButton(a, a.getString(R.string.ch_erase)) { ChronoEngine.clear(a, records = false); main() }, null))
        Rows.add(erase, Rows.row(a, a.getString(R.string.ch_clear_all), null,
            Rows.actionButton(a, a.getString(R.string.ch_erase), danger = true) {
                Windows.confirm(a, a.getString(R.string.ch_clear_all), a.getString(R.string.ch_clear_all_q), a.getString(R.string.ch_erase)) { ChronoEngine.clear(a, records = true); main() }
            }, null))
        right.addView(erase, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
    }

    private fun toggle(group: LinearLayout, title: String, sub: String?, key: String) {
        val sw = SwitchView(a).apply { on = prefs.bool(key, true) }
        Rows.add(group, Rows.row(a, title, sub, sw) { prefs.setBool(key, !prefs.bool(key, true)); sw.on = prefs.bool(key, true) })
    }

    /** A row of the table, its columns at fixed shares. */
    private fun line(cells: List<String>, header: Boolean): LinearLayout {
        val weights = floatArrayOf(1.25f, 0.9f, 1f, 1.1f, 0.9f, 0.75f, 0.9f, 0.7f, 0.3f)
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        cells.forEachIndexed { i, s ->
            val g = if (i == 0) Gravity.START else Gravity.END
            val v: View = when {
                !header && s == SLOPE -> LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    addView(SlopeView(a), LinearLayout.LayoutParams(dp(22), dp(18)).apply { rightMargin = dp(6) })
                    addView(tv(a.getString(R.string.ch_slope), 15f, col(R.color.text_disabled)))
                }
                header -> tv(if (s == "kW") s else s.uppercase(), 12f, col(R.color.text_title), Typeface.create("sans-serif-medium", Typeface.NORMAL), g).apply { letterSpacing = 0.08f }
                s == RECORD -> tv(a.getString(R.string.ch_record_run), 16f, k.orange, Typeface.create("sans-serif-medium", Typeface.NORMAL), g)
                i == 2 -> tv(s, 19f, col(R.color.text_value), Typeface.create("sans-serif-medium", Typeface.NORMAL), g)
                i == 8 -> tv(s, 24f, col(R.color.text_title), gravity = g)
                else -> tv(s, 16f, if (i == 0 || i >= 4) col(R.color.text_sub) else col(R.color.text_value), gravity = g)
            }
            row.addView(v, LinearLayout.LayoutParams(0, -1, weights[i]))
        }
        return row
    }

    // ── Detail of a run ──

    private fun detail(run: ChronoRunData) {
        root.removeAllViews()
        val h = ChronoEngine.history.value
        val rec = h.record(if (run.kind == ChronoMath.Kind.STANDING) ChronoHistory.Rec.FULL else ChronoHistory.Rec.ROLLING, run.mph)
        val bar = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(tv("‹", 34f, col(R.color.text_value)).apply { setPadding(dp(8), 0, dp(20), dp(4)); Rows.touchable(this); setOnClickListener { main() } })
        bar.addView(tv("${k.date(run.at)} · ${k.label(run.kind)} · ${k.time(run.full)} s", 21f, face = Typeface.create("sans-serif-medium", Typeface.NORMAL)), LinearLayout.LayoutParams(0, -2, 1f))
        if (!ChronoHistory.counts(run)) bar.addView(tv(a.getString(R.string.ch_downhill, k.climb(run.climb)), 15f, col(R.color.text_sub)))
        root.addView(bar, LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(ChartView(a, run, rec), LinearLayout.LayoutParams(-1, dp(330)).apply { topMargin = dp(8) })

        val tiles = listOf(
            k.label(ChronoMath.Kind.STANDING, true) to (run.split?.let { "${k.time(it)} s" } ?: "—"),
            k.label(run.kind) to "${k.time(run.full)} s",
            a.getString(R.string.ch_peak_kw) to (run.maxKw?.let { m -> k.kw(m) + (run.maxKwAt?.let { " · ${k.speed(it.toDouble())} ${k.unit}" } ?: "") } ?: "—"),
            a.getString(R.string.ch_distance) to k.dist(run.distance),
            a.getString(R.string.ch_battery) to (run.soc?.let { "$it %" } ?: "—"),
            a.getString(R.string.ch_outside) to k.temp(run.outside),
            a.getString(R.string.ch_climb) to k.climb(run.climb),
            a.getString(R.string.ch_during) to listOfNotNull(a.getString(if (run.sport) R.string.dm_sport else R.string.ch_mode_kept),
                if (run.climateCut) a.getString(R.string.ch_climate_off) else null).joinToString(" · "),
        )
        val grid = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        tiles.chunked(4).forEach { chunk ->
            val rowv = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEachIndexed { i, (label, v) ->
                rowv.addView(LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL; background = Rows.groupBackground(a); setPadding(dp(18), dp(12), dp(12), dp(12))
                    addView(tv(label.uppercase(), 12f, col(R.color.text_title), Typeface.create("sans-serif-medium", Typeface.NORMAL)).apply { letterSpacing = 0.1f })
                    addView(tv(v, 22f), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(12) })
            }
            grid.addView(rowv, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        val bottom = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bottom.addView(SpeedView(a, run), LinearLayout.LayoutParams(0, dp(170), 1f))
        val side = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        if (run.gps) side.addView(tv(a.getString(R.string.ch_opt_gps), 15f, col(R.color.text_sub), gravity = Gravity.END))
        side.addView(Rows.actionButton(a, a.getString(R.string.ch_delete_run), danger = true) {
            Windows.confirm(a, a.getString(R.string.ch_delete_run), null, a.getString(R.string.ch_erase)) { ChronoEngine.removeRun(a, run.at); main() }
        }, LinearLayout.LayoutParams(-2, dp(56)).apply { topMargin = dp(14) })
        bottom.addView(side, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(24) })
        root.addView(bottom, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
    }

    @SuppressLint("ViewConstructor")
    private class TrophyView(c: Activity, private val on: Boolean) : View(c) {
        private val k = ChronoDraw(c)
        override fun onDraw(cv: Canvas) = k.trophy(cv, width / 2f, height / 2f, width.toFloat(), if (on) k.orange else k.alpha(k.sub, 0.4f))
    }

    @SuppressLint("ViewConstructor")
    private class SlopeView(c: Activity) : View(c) {
        private val k = ChronoDraw(c)
        override fun onDraw(cv: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            k.p.style = android.graphics.Paint.Style.STROKE; k.p.strokeWidth = 2 * k.d; k.p.strokeCap = android.graphics.Paint.Cap.ROUND
            k.p.color = Themes.color(context, R.color.text_disabled)
            cv.drawLine(w * 0.08f, h * 0.2f, w * 0.92f, h * 0.85f, k.p)
            cv.drawLine(w * 0.92f, h * 0.85f, w * 0.6f, h * 0.85f, k.p); cv.drawLine(w * 0.92f, h * 0.85f, w * 0.88f, h * 0.5f, k.p)
        }
    }

    /** The power against speed; a touch reads the value under the finger. */
    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private class ChartView(c: Activity, private val run: ChronoRunData, private val rec: ChronoRunData?) : View(c) {
        private val k = ChronoDraw(c)
        private var touchV: Float? = null
        private val box = RectF()
        override fun onDraw(cv: Canvas) {
            box.set(0f, 0f, width.toFloat(), height.toFloat())
            k.curve(cv, box, run, rec, axes = true, legend = true, touchV = touchV)
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val t = ChronoMath.targets(run.kind, run.mph)
            val left = 44 * k.d
            touchV = if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) null
                else (t.from + (e.x - left) / (width - left) * (t.to - t.from)).toFloat()
            parent?.requestDisallowInterceptTouchEvent(touchV != null)
            invalidate(); return true
        }
    }

    @SuppressLint("ViewConstructor")
    private class SpeedView(c: Activity, private val run: ChronoRunData) : View(c) {
        private val k = ChronoDraw(c)
        override fun onDraw(cv: Canvas) {
            k.text(cv, context.getString(R.string.ch_speed_time).uppercase(), 44 * k.d, 14 * k.d, 12f, k.title, k.medium, spacing = 0.1f)
            k.speedTime(cv, RectF(0f, 22 * k.d, width.toFloat(), height.toFloat()), run)
        }
    }

    private companion object {
        const val SLOPE = "\u0000slope"
        const val RECORD = "\u0000record"
    }
}
