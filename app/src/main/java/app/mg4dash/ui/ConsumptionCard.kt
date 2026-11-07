package app.mg4dash.ui

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.mg4dash.R
import app.mg4dash.energy.EnergyMonitor
import java.util.Locale

/**
 * "Consumption" page of the map widget (issue #6): same content as on the Energy page,
 * since the last charge: total, driving / climate / accessories breakdown, recovered
 * energy, and curve of the last km. Shown automatically when the map has no connection.
 */
class ConsumptionCard(context: Context) : LinearLayout(context) {
    private val d = resources.displayMetrics.density
    private fun c(res: Int) = Themes.color(context, res)
    private fun tv(sp: Float, colorRes: Int) = TextView(context).apply {
        setTextColor(c(colorRes)); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); maxLines = 1
    }
    private val total = tv(40f, R.color.text_value)
    /** Period's distance (#44), to the right of the total, small unit as on the Energy card. */
    private val distance = tv(40f, R.color.text_value)
    private val regenLabel = tv(13f, R.color.text_sub).apply { text = context.getString(R.string.en_regen_short).uppercase(); letterSpacing = 0.1f; includeFontPadding = false }
    private val regen = tv(20f, R.color.accent_energy).apply { setTextColor(Themes.energy(context)); includeFontPadding = false }
    private val legend = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val chart = LineChart(context).apply {
        // Long press on the curve: erase it (#44)
        setOnLongClickListener { v -> (context as? android.app.Activity)?.let { confirmClear(it, v, prefs.consumptionPeriod.coerceIn(0, 2)) }; true }
    }

    private val note = tv(15f, R.color.text_sub)
    /** Duration of the current trip, without seconds, in the top right corner (#44); only for the trip. */
    private val duration = tv(19f, R.color.text_value).apply { gravity = Gravity.END }
    /** Average and last km, in colour, on the chart's title line instead of bubbles on the curve (#44). */
    private val figures = tv(15f, R.color.text_sub).apply { gravity = Gravity.END }
    private val tick = object : Runnable {
        override fun run() { showDuration(); postDelayed(this, 30_000) }
    }

    private val prefs = app.mg4dash.system.Prefs(context)
    private val periodLabels = listOf(R.string.en_since_charge, R.string.en_trip, R.string.en_last_year).map { context.getString(it) }
    private val periodTv: TextView =
        tv(19f, R.color.text_value).apply {
            // Taller touch zone for the period's choice
            setPadding(0, (14 * d).toInt(), (24 * d).toInt(), (14 * d).toInt())
            isClickable = true
            // A tap goes to the next period (#44): only 3, a menu was one step too many
            setOnClickListener { prefs.consumptionPeriod = (prefs.consumptionPeriod.coerceIn(0, 2) + 1) % 3; show() }
        }

    /** Totals on the period's line in a small place (#97). */
    private val summary = tv(24f, R.color.text_value).apply { visibility = GONE }
    private lateinit var totalRow: View

    /**
     * Small place (#97): the totals move to the period's line, the breakdown and the note go,
     * so the curve keeps its height.
     */
    var compact = false
        set(v) {
            if (field == v) return
            field = v
            totalRow.visibility = if (v) GONE else VISIBLE
            legend.visibility = if (v) GONE else VISIBLE
            note.visibility = if (v) GONE else VISIBLE
            summary.visibility = if (v) VISIBLE else GONE
            setPadding((20 * d).toInt(), ((if (v) 8 else 16) * d).toInt(), (20 * d).toInt(), ((if (v) 10 else 16) * d).toInt())
            show()
        }

    init {
        // No Energy page on a tap (the bar has its button): it opened by mistake when aiming at the period
        orientation = VERTICAL
        setPadding((20 * d).toInt(), (16 * d).toInt(), (20 * d).toInt(), (16 * d).toInt())
        // Title hidden with the "Hide titles" option (the space stays, so nothing shifts)
        // Period ("Since the charge"): a tap goes to the next one (#44)
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(periodTv, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(summary, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { rightMargin = (16 * d).toInt() })
            addView(duration, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        totalRow = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.BOTTOM
            addView(total, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            // On the big figure's baseline, through the values' line
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                addView(regenLabel)
                addView(regen)
                baselineAlignedChildIndex = 1
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = (14 * d).toInt() })
            addView(distance)
        }
        addView(totalRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = (10 * d).toInt() })
        addView(legend, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = (6 * d).toInt() })
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.BOTTOM
            addView(tv(15f, R.color.text_title).apply { text = context.getString(R.string.en_recent, app.mg4dash.system.Units.stackUnit(context)); letterSpacing = 0.1f },
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(figures, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = (10 * d).toInt() })
        chart.averageBubble = false
        chart.averageColor = c(R.color.text_sub)
        addView(chart, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(note)
    }

    fun show() {
        val period = prefs.consumptionPeriod.coerceIn(0, 2)
        periodTv.text = periodLabels[period]
        val t = EnergyMonitor.totals(period)
        val ok = t.outKwh > 0f
        // Regeneration next to the total (#44), in green, small unit; nothing if it is zero
        total.text = if (!ok) "—" else smallUnit(String.format(Locale.getDefault(), "%.1f kWh", t.outKwh))
        val hasRegen = ok && t.regenKwh >= 0.05f
        regen.text = if (hasRegen) regenValues(context, t.regenKwh, regenKm(t)) else ""
        regenLabel.visibility = if (hasRegen) VISIBLE else INVISIBLE
        distance.text = if (t.distKm >= 1f) smallUnit(app.mg4dash.system.Units.distText(context, t.distKm)) else ""
        summary.text = if (distance.text.isNullOrEmpty()) total.text else android.text.TextUtils.concat(total.text, "   ", distance.text)
        fillLegend(context, legend, t)
        val (points, n) = fill(context, chart, period, stacked = true)
        axis(context, period, points).let { (a, b) -> chart.startLabel = a; chart.endLabel = b }
        chart.emptyText = if (period == 2) R.string.chart_empty_trips else R.string.chart_empty
        note.text = n
        figures.text = figuresText(points)
        // Dot of the last km at the curve's end, not on the 12 months (one point per trip)
        chart.lastDotColor = if (period != 2 && points >= 2 && lastKm(context) != null) c(R.color.text_sub) else null
        showDuration()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); removeCallbacks(tick); post(tick) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

    private fun showDuration() {
        val start = EnergyMonitor.tripStartedAt
        duration.text = if (prefs.consumptionPeriod.coerceIn(0, 2) == 1 && start > 0) durationText(context, System.currentTimeMillis() - start) else ""
    }

    /**
     * "- - - 14.8   ● 22.6" (#44): the figures in white, without colour; a grey dash before the average
     * (as its line) and a grey dot before the last km (as on the curve's end). TalkBack still reads "avg." and "last km".
     */
    private fun figuresText(points: Int): CharSequence {
        if (points < 2) { figures.contentDescription = null; return "" }
        val out = android.text.SpannableStringBuilder()
        val spoken = mutableListOf<String>()
        fun add(marker: String, value: Float, label: Int) {
            if (out.isNotEmpty()) out.append("    ")
            var at = out.length
            out.append(marker).append(" ")
            out.setSpan(android.text.style.ForegroundColorSpan(c(R.color.text_sub)), at, out.length, 0)
            at = out.length
            val v = String.format(Locale.getDefault(), "%.1f", value)
            out.append(v)
            out.setSpan(android.text.style.ForegroundColorSpan(c(R.color.text_value)), at, out.length, 0)
            out.setSpan(android.text.style.RelativeSizeSpan(1.25f), at, out.length, 0)
            spoken += "$v ${context.getString(label)}"
        }
        add("- - -", chart.averageValue, R.string.cc_avg)
        lastKm(context)?.let { add("●", it, R.string.cc_last_km) }
        figures.contentDescription = spoken.joinToString(", ")
        return out
    }

    /** Half size unit after the last space ("1.6 kWh", "12 km"). */
    private fun smallUnit(t: String): CharSequence {
        val i = t.lastIndexOf(' ').takeIf { it > 0 } ?: return t
        return android.text.SpannableString(t).apply { setSpan(android.text.style.RelativeSizeSpan(0.5f), i, t.length, 0) }
    }

    companion object {
        /** Consumption of the last km of the current trip, in the chart's unit; null without a trip point (#44). */
        fun lastKm(c: android.content.Context): Float? =
            app.mg4dash.energy.ConsumptionCurve.curves.value.trip.lastOrNull()?.let { app.mg4dash.system.Units.stackValue(c, it) }

        /** "1 h 12" or "25 min" (#44). */
        fun durationText(c: android.content.Context, ms: Long): String {
            val min = (ms / 60_000L).coerceAtLeast(0).toInt()
            return if (min < 60) c.getString(R.string.cc_min, min) else c.getString(R.string.cc_dur_hm, min / 60, min % 60)
        }

        /** Part of the distance powered by the recovered energy, its share of what left the battery (#44, see the README). */
        fun regenKm(t: EnergyMonitor.Totals): Float? =
            if (t.distKm >= 2f && t.outKwh > 0f) t.regenKwh / t.outKwh * t.distKm else null

        /** "−3.2 kWh   ~19.5 km": energy deducted from the total, km it powered (not added to the range, #44); no km if unknown or out of range. */
        fun regenValues(c: android.content.Context, kwh: Float, km: Float?): String {
            val kmText = km?.takeIf { it > 0.05f && it < 2000f }?.let { "   ~" + app.mg4dash.system.Units.distText(c, it, 1) } ?: ""
            return String.format(Locale.getDefault(), "−%.1f kWh", kwh) + kmText
        }

        /** Asks for confirmation then erases the curve of [period] (0 charge, 1 trip, 2 12 months). */
        fun confirmClear(a: android.app.Activity, anchor: android.view.View, period: Int) {
            val name = a.getString(listOf(R.string.en_since_charge, R.string.en_trip, R.string.en_last_year)[period])
            SourceMenu.confirm(a, anchor, a.getString(R.string.en_clear_confirm, name), a.getString(R.string.en_clear_curve)) {
                app.mg4dash.energy.ConsumptionCurve.clear(period)
                app.mg4dash.energy.EnergyMonitor.reset(period)
            }
        }

        /**
         * Horizontal scale (#44): how many km ago (one point per km) or, over 12 months, how long
         * ago (age of the oldest trip) the curve starts; the end is "now".
         */
        fun axis(c: android.content.Context, period: Int, points: Int): Pair<String?, String?> {
            if (points < 2) return null to null
            val curves = app.mg4dash.energy.ConsumptionCurve.curves.value
            val dated = curves.chargeTimes.firstOrNull().takeIf { period == 0 }
            val start = if (period == 2 || dated != null) {
                val at = dated ?: curves.firstTripAt ?: return null to null
                val min = ((System.currentTimeMillis() - at) / 60_000L).coerceAtLeast(1)
                val (res, n) = when {
                    min < 60 -> R.string.cc_min to min
                    min < 24 * 60 -> R.string.cc_hours to min / 60
                    min < 14 * 24 * 60 -> R.string.cc_days to min / (24 * 60)
                    min < 9 * 7 * 24 * 60 -> R.string.cc_weeks to min / (7 * 24 * 60)
                    else -> R.string.cc_months to min / (30 * 24 * 60)
                }
                c.getString(res, n.toInt())
            } else app.mg4dash.system.Units.distText(c, points.toFloat())
            return c.getString(R.string.cc_ago, start) to c.getString(R.string.cc_now)
        }

        /**
         * Fills the period's chart and returns (number of original points, legend) (#44): trip by km,
         * smoothed over 3 points; charge and 12 months by date. Charge points from before dates stay by km.
         */
        fun fill(c: android.content.Context, chart: LineChart, period: Int, stacked: Boolean = false): Pair<Int, String> {
            val curves = app.mg4dash.energy.ConsumptionCurve.curves.value
            chart.scaleTops = if (stacked) CURVE_TOPS.map { app.mg4dash.system.Units.stackValue(c, it) } else null
            // Stacked chart: in kWh per 100 km or 100 miles, the only unit whose items add up
            val v = curves.of(period).map { if (stacked) app.mg4dash.system.Units.stackValue(c, it) else app.mg4dash.system.Units.cons(c, it) }
            val mean = if (stacked) periodMean(c, period) ?: v.average().toFloat() else v.average().toFloat()
            if (stacked && showStack(c, chart, curves, period, v, mean)) {
                return v.size to if (period == 2) c.getString(R.string.en_trips_note, v.size)
                    else c.getString(if (period == 0 && curves.chargeTimes.size == v.size) R.string.en_time_note else R.string.en_curve_note)
            }
            val line = app.mg4dash.energy.ConsumptionCurve.timeLine(curves.timesOf(period), v)
            if (line.size >= 2) {
                chart.showTimeline(line, mean)
                return v.size to if (period == 2) c.getString(R.string.en_trips_note, v.size) else c.getString(R.string.en_time_note)
            }
            chart.values = if (period == 2) v else v.smoothed()
            return v.size to curveNote(c, period, v)
        }

        /** Stacked chart (Energy page), bottom to top: the steady items first, so their size can be read, driving last (#44); points without composition form a grey layer. */
        private fun showStack(c: android.content.Context, chart: LineChart, curves: app.mg4dash.energy.ConsumptionCurve.Curves, period: Int, v: List<Float>, mean: Float): Boolean {
            val mixes = curves.mixOf(period)
            if (v.size < 2 || mixes.size != v.size || mixes.all { it == null }) return false
            val colors = listOf(Themes.color(c, R.color.text_sub), Themes.color(c, R.color.accent_cold), Themes.color(c, R.color.accent_heat),
                Themes.color(c, R.color.accent_battery), Themes.color(c, R.color.accent_energy))
            val parts = listOf<List<Float>>(v.indices.map { i -> if (mixes[i] == null) v[i] else 0f }) +
                listOf(1, 3, 2, 0).map { k -> v.indices.map { i -> mixes[i]?.parts()?.get(k)?.let { app.mg4dash.system.Units.stackValue(c, it) } ?: 0f } }
            val times = curves.timesOf(period)
            if (times.size == v.size) {
                val lines = parts.map { app.mg4dash.energy.ConsumptionCurve.timeLine(times, it) }
                if (lines[0].size < 2) return false
                chart.showLayers(lines[0].map { it.first }, lines.mapIndexed { k, l -> LineChart.Layer(colors[k], l.map { it.second }) }, mean)
            } else {
                chart.showLayers(null, parts.mapIndexed { k, l -> LineChart.Layer(colors[k], l.smoothed().atMost(50)) }, mean)
            }
            return true
        }

        /** Tops of the curve in kWh/100 km: a peak no longer flattens everything else (#44). */
        private val CURVE_TOPS = listOf(20f, 30f)

        /** Real average of the period (#44): energy out of the battery, minus regeneration, over the distance. The points are only for the picture. */
        private fun periodMean(c: android.content.Context, period: Int): Float? {
            val t = EnergyMonitor.totals(period)
            // Same average as the energy card's, with the distance to the tenth of a km (#29)
            return t.per100?.let { app.mg4dash.system.Units.stackValue(c, it) }
        }

        /** Compact legend: colour, item and share of each, driving with regeneration deducted. */
        private fun fillLegend(c: android.content.Context, legend: LinearLayout, t: EnergyMonitor.Totals) {
            legend.removeAllViews()
            val drive = (t.driveKwh - t.regenKwh).coerceAtLeast(0f)
            val all = drive + t.climKwh + t.heatKwh + t.accKwh
            if (all <= 0f) return
            val d = c.resources.displayMetrics.density
            listOfNotNull(Triple(R.string.sw_drive, drive, R.color.accent_energy), Triple(R.string.sw_clim, t.climKwh, R.color.accent_cold),
                Triple(R.string.sw_batt, t.heatKwh, R.color.accent_battery).takeIf { t.heatKwh >= EnergyMonitor.HEAT_SHOWN_KWH },
                Triple(R.string.sw_acc, t.accKwh, R.color.accent_heat)).forEach { (label, v, col) ->
                legend.addView(View(c).apply {
                    background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Themes.color(c, col)) }
                }, LayoutParams((10 * d).toInt(), (10 * d).toInt()))
                legend.addView(TextView(c).apply {
                    text = "${c.getString(label)} ${Math.round(v / all * 100)} %"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(Themes.color(c, R.color.text_sub))
                    maxLines = 1
                }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = (5 * d).toInt(); rightMargin = (12 * d).toInt() })
            }
        }

        private fun curveNote(c: android.content.Context, period: Int, v: List<Float>): String {
            // Average without its value in the legend: the dashed line's label already gives it (#44)
            return if (period == 2) (if (v.size >= 2) c.getString(R.string.en_trips_note, v.size) else c.getString(R.string.en_trips_wait))
            else (if (v.size >= 2) c.getString(R.string.en_curve_note) else c.getString(R.string.en_curve_wait))
        }
    }
}
