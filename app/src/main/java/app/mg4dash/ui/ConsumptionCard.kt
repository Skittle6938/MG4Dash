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
    /** Map layout: same detail as the Energy page, with its ring. Full layout: no ring, it takes too much room. */
    private val withRing = app.mg4dash.system.Prefs(context).homeStyle == 2
    private val regenLabel = tv(13f, R.color.text_sub).apply { text = context.getString(R.string.en_regen_short).uppercase(); letterSpacing = 0.1f; includeFontPadding = false }
    private val regen = tv(20f, R.color.accent_energy).apply { setTextColor(Themes.energy(context)); includeFontPadding = false }
    private val ring = RingChart(context)
    private val legend = LinearLayout(context).apply { orientation = if (withRing) VERTICAL else HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val chart = LineChart(context).apply {
        // Long press on the curve: erase it (#44)
        setOnLongClickListener { v -> (context as? android.app.Activity)?.let { confirmClear(it, v, prefs.consumptionPeriod.coerceIn(0, 2)) }; true }
        setOnClickListener { openEnergy() }
    }

    /** A touch opens the Energy page, like the battery widget. */
    private fun openEnergy() = context.startActivity(android.content.Intent(context, app.mg4dash.pages.EnergyActivity::class.java))
    private val note = tv(15f, R.color.text_sub)

    private val prefs = app.mg4dash.system.Prefs(context)
    private val periodLabels = listOf(R.string.en_since_charge, R.string.en_trip, R.string.en_last_year).map { context.getString(it) }
    private val periodTv: TextView =
        tv(19f, R.color.text_value).apply {
            setPadding(0, (6 * d).toInt(), (24 * d).toInt(), (6 * d).toInt())
            isClickable = true
            setOnClickListener { v ->
                (context as? android.app.Activity)?.let { a ->
                    SourceMenu.choose(a, v, periodLabels, prefs.consumptionPeriod) { prefs.consumptionPeriod = it; show() }
                }
            }
        }

    init {
        setOnClickListener { openEnergy() }
        orientation = VERTICAL
        setPadding((20 * d).toInt(), (16 * d).toInt(), (20 * d).toInt(), (16 * d).toInt())
        // Title hidden with the "Hide titles" option (the space stays, so nothing shifts)
        // Period ("Since the charge ▾"): a touch opens the choice, like the radio source choice
        addView(periodTv, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.BOTTOM
            addView(total, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                addView(regenLabel)
                addView(regen)
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = (14 * d).toInt(); bottomMargin = (9 * d).toInt() })
            addView(distance)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = (10 * d).toInt() })
        if (withRing) addView(LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(ring, LayoutParams((104 * d).toInt(), (104 * d).toInt()))
            addView(legend, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = (16 * d).toInt() })
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = (6 * d).toInt() })
        else addView(legend, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = (6 * d).toInt() })
        addView(tv(15f, R.color.text_title).apply { text = context.getString(R.string.en_recent, app.mg4dash.system.Units.stackUnit(context)); letterSpacing = 0.1f },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = (10 * d).toInt() })
        addView(chart, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(note)
    }

    fun show() {
        val period = prefs.consumptionPeriod.coerceIn(0, 2)
        periodTv.text = periodLabels[period] + "  ▾"
        val t = EnergyMonitor.totals(period)
        val ok = t.outKwh > 0f
        // Regeneration next to the total (#44), in green, small unit; nothing if it is zero
        total.text = if (!ok) "—" else smallUnit(String.format(Locale.getDefault(), "%.1f kWh", t.outKwh))
        val hasRegen = ok && t.regenKwh >= 0.05f
        regen.text = if (hasRegen) regenValues(context, t.regenKwh, if (period == 0) carRegenKm() else regenKmEstimate(t)) else ""
        regenLabel.visibility = if (hasRegen) VISIBLE else INVISIBLE
        distance.text = if (t.km > 0) smallUnit(app.mg4dash.system.Units.distText(context, t.km.toFloat())) else ""
        if (withRing) fillRing(t) else fillLegend(context, legend, t)
        val (points, n) = fill(context, chart, period, stacked = true)
        axis(context, period, points).let { (a, b) -> chart.startLabel = a; chart.endLabel = b }
        chart.emptyText = if (period == 2) R.string.chart_empty_trips else R.string.chart_empty
        note.text = n
    }

    private fun carRegenKm(): Float? =
        if (app.mg4dash.BuildConfig.MOCK) 50.9f else app.mg4dash.car.CarProps.getFloat(app.mg4dash.car.CarProps.REGEN_RNG_AFT_CHRG)

    /** Ring and detail, like the Energy page (Map layout). */
    private fun fillRing(t: EnergyMonitor.Totals) {
        legend.removeAllViews()
        val drive = (t.driveKwh - t.regenKwh).coerceAtLeast(0f)
        val all = drive + t.climKwh + t.heatKwh + t.accKwh
        if (all <= 0f) { ring.show(emptyList(), ""); return }
        val items = listOfNotNull(Triple(R.string.en_drive, drive, R.color.accent_energy), Triple(R.string.en_climate, t.climKwh, R.color.accent_cold),
            Triple(R.string.en_battery_heat, t.heatKwh, R.color.accent_battery).takeIf { t.heatKwh >= EnergyMonitor.HEAT_SHOWN_KWH },
            Triple(R.string.en_accessories, t.accKwh, R.color.accent_heat))
        ring.show(items.map { it.second to c(it.third) }, "${Math.round(drive / all * 100)} %")
        items.forEach { (label, v, col) ->
            legend.addView(LinearLayout(context).apply {
                orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(View(context).apply { setBackgroundColor(c(col)) }, LayoutParams((12 * d).toInt(), (12 * d).toInt()))
                addView(tv(17f, R.color.text_sub).apply { text = context.getString(label) }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = (10 * d).toInt() })
                addView(tv(17f, R.color.text_value).apply { text = String.format(Locale.getDefault(), "%.1f kWh · %d %%", v, Math.round(v / all * 100)) })
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    /** Half size unit after the last space ("1.6 kWh", "12 km"). */
    private fun smallUnit(t: String): CharSequence {
        val i = t.lastIndexOf(' ').takeIf { it > 0 } ?: return t
        return android.text.SpannableString(t).apply { setSpan(android.text.style.RelativeSizeSpan(0.5f), i, t.length, 0) }
    }

    companion object {
        /** Range the recovered energy gave back at the period's consumption; the car only counts it since the last charge. */
        fun regenKmEstimate(t: EnergyMonitor.Totals): Float? {
            val net = t.outKwh - t.regenKwh
            return if (t.km >= 2 && net > 0f) t.regenKwh * t.km / net else null
        }

        /** "+3.2 kWh   +50.9 km"; no km if unknown or out of range. */
        fun regenValues(c: android.content.Context, kwh: Float, km: Float?): String {
            val kmText = km?.takeIf { it > 0.05f && it < 2000f }?.let { "   +" + app.mg4dash.system.Units.distText(c, it, 1) } ?: ""
            return String.format(Locale.getDefault(), "+%.1f kWh", kwh) + kmText
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
            // Stacked chart: in kWh per 100 km or 100 miles, the only unit whose items add up
            val v = curves.of(period).map { if (stacked) app.mg4dash.system.Units.stackValue(c, it) else app.mg4dash.system.Units.cons(c, it) }
            if (stacked && showStack(c, chart, curves, period, v)) {
                return v.size to if (period == 2) c.getString(R.string.en_trips_note, v.size)
                    else c.getString(if (period == 0 && curves.chargeTimes.size == v.size) R.string.en_time_note else R.string.en_curve_note)
            }
            val line = app.mg4dash.energy.ConsumptionCurve.timeLine(curves.timesOf(period), v)
            if (line.size >= 2) {
                chart.showTimeline(line, v.average().toFloat())
                return v.size to if (period == 2) c.getString(R.string.en_trips_note, v.size) else c.getString(R.string.en_time_note)
            }
            chart.values = if (period == 2) v else v.smoothed()
            return v.size to curveNote(c, period, v)
        }

        /** Stacked chart (Energy page): one item per layer; points without composition form a grey layer. */
        private fun showStack(c: android.content.Context, chart: LineChart, curves: app.mg4dash.energy.ConsumptionCurve.Curves, period: Int, v: List<Float>): Boolean {
            val mixes = curves.mixOf(period)
            if (v.size < 2 || mixes.size != v.size || mixes.all { it == null }) return false
            val colors = listOf(Themes.color(c, R.color.text_sub), Themes.color(c, R.color.accent_energy), Themes.color(c, R.color.accent_cold),
                Themes.color(c, R.color.accent_battery), Themes.color(c, R.color.accent_heat))
            val parts = listOf<List<Float>>(v.indices.map { i -> if (mixes[i] == null) v[i] else 0f }) +
                (0..3).map { k -> v.indices.map { i -> mixes[i]?.parts()?.get(k)?.let { app.mg4dash.system.Units.stackValue(c, it) } ?: 0f } }
            val times = curves.timesOf(period)
            if (times.size == v.size) {
                val lines = parts.map { app.mg4dash.energy.ConsumptionCurve.timeLine(times, it) }
                if (lines[0].size < 2) return false
                chart.showLayers(lines[0].map { it.first }, lines.mapIndexed { k, l -> LineChart.Layer(colors[k], l.map { it.second }) }, v.average().toFloat())
            } else {
                chart.showLayers(null, parts.mapIndexed { k, l -> LineChart.Layer(colors[k], l.smoothed().atMost(50)) }, v.average().toFloat())
            }
            return true
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
