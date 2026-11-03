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
 * Page « Consommation » du widget carte (issue #6) : même contenu que sur la page Énergie,
 * depuis la dernière recharge — total, répartition conduite / clim / accessoires, énergie
 * récupérée, et courbe des derniers km. Affichée d'office quand la carte n'a pas de connexion.
 */
class ConsumptionCard(context: Context) : LinearLayout(context) {
    private val d = resources.displayMetrics.density
    private fun c(res: Int) = Themes.color(context, res)
    private fun tv(sp: Float, colorRes: Int) = TextView(context).apply {
        setTextColor(c(colorRes)); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); maxLines = 1
    }
    private val total = tv(40f, R.color.text_value)
    /** Distance de la période (#44), à droite du total, unité en petit comme sur la carte Énergie. */
    private val distance = tv(40f, R.color.text_value)
    private val legend = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val chart = LineChart(context).apply {
        // Appui long sur la courbe : l'effacer (#44)
        setOnLongClickListener { v -> (context as? android.app.Activity)?.let { confirmClear(it, v, prefs.consumptionPeriod.coerceIn(0, 2)) }; true }
    }
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
        orientation = VERTICAL
        setPadding((20 * d).toInt(), (16 * d).toInt(), (20 * d).toInt(), (16 * d).toInt())
        // Titre masqué avec l'option « Masquer les titres » (la place reste, pour ne rien décaler)
        addView(tv(17f, R.color.text_title).apply { text = context.getString(R.string.en_consumption); letterSpacing = 0.12f
            if (app.mg4dash.system.Prefs(context).hideTitles) visibility = INVISIBLE })
        // Période (« Depuis la recharge ▾ ») : un toucher ouvre le choix, comme le choix de la source radio
        addView(periodTv, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.BOTTOM
            addView(total, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(distance)
        })
        addView(legend, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * d).toInt() })
        addView(tv(15f, R.color.text_title).apply { text = context.getString(R.string.en_recent, app.mg4dash.system.Units.consUnit(context)); letterSpacing = 0.1f },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = (10 * d).toInt() })
        addView(chart, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(note)
    }

    fun show() {
        val period = prefs.consumptionPeriod.coerceIn(0, 2)
        periodTv.text = periodLabels[period] + "  ▾"
        val t = EnergyMonitor.totals(period)
        val ok = t.outKwh > 0f
        // Récupération à côté du total (#44), en vert, unité en petit ; rien si elle est nulle
        total.text = if (!ok) "—" else android.text.SpannableStringBuilder(smallUnit(String.format(Locale.getDefault(), "%.1f kWh", t.outKwh))).apply {
            if (t.regenKwh >= 0.05f) {
                val st = length
                append("  −").append(String.format(Locale.getDefault(), "%.1f", t.regenKwh))   // signe « − » : énergie rendue (#44)
                val unit = length
                append(" ").append(context.getString(R.string.en_regen_short))
                setSpan(android.text.style.ForegroundColorSpan(Themes.energy(context)), st, length, 0)
                setSpan(android.text.style.RelativeSizeSpan(0.6f), st, unit, 0)
                setSpan(android.text.style.RelativeSizeSpan(0.4f), unit, length, 0)
            }
        }
        distance.text = if (t.km > 0) smallUnit(app.mg4dash.system.Units.distText(context, t.km.toFloat())) else ""
        fillLegend(context, legend, t)
        val (points, n) = fill(context, chart, period)
        axis(context, period, points).let { (a, b) -> chart.startLabel = a; chart.endLabel = b }
        chart.emptyText = if (period == 2) R.string.chart_empty_trips else R.string.chart_empty
        note.text = n
    }

    /** Unité à mi-taille après le dernier espace (« 1.6 kWh », « 12 km »). */
    private fun smallUnit(t: String): CharSequence {
        val i = t.lastIndexOf(' ').takeIf { it > 0 } ?: return t
        return android.text.SpannableString(t).apply { setSpan(android.text.style.RelativeSizeSpan(0.5f), i, t.length, 0) }
    }

    companion object {
        /** Demande confirmation puis efface la courbe de [period] (0 recharge, 1 trajet, 2 12 mois). */
        fun confirmClear(a: android.app.Activity, anchor: android.view.View, period: Int) {
            val name = a.getString(listOf(R.string.en_since_charge, R.string.en_trip, R.string.en_last_year)[period])
            SourceMenu.confirm(a, anchor, a.getString(R.string.en_clear_confirm, name), a.getString(R.string.en_clear_curve)) {
                app.mg4dash.energy.ConsumptionCurve.clear(period)
                app.mg4dash.energy.EnergyMonitor.reset(period)
            }
        }

        /**
         * Échelle horizontale (#44) : depuis combien de km (un point par km) ou, sur 12 mois, depuis combien
         * de temps (âge du plus ancien trajet) commence la courbe ; la fin est « maintenant ».
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
         * Remplit le graphique de la période et rend (nombre de points d'origine, légende) (#44) : trajet par km,
         * lissé sur 3 points ; recharge et 12 mois selon la date. Les points de recharge d'avant les dates restent par km.
         */
        fun fill(c: android.content.Context, chart: LineChart, period: Int, stacked: Boolean = false): Pair<Int, String> {
            val curves = app.mg4dash.energy.ConsumptionCurve.curves.value
            // Graphique empilé : en kWh pour 100 km ou 100 miles, seule unité dont les postes s'additionnent
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

        /** Graphique empilé (page Énergie) : un poste par couche ; les points sans composition forment une couche grise. */
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

        /** Légende compacte : couleur, poste et part de chacun, conduite récupération déduite. */
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
            // Moyenne sans sa valeur dans la légende : l'étiquette de la ligne pointillée la donne déjà (#44)
            return if (period == 2) (if (v.size >= 2) c.getString(R.string.en_trips_note, v.size) else c.getString(R.string.en_trips_wait))
            else (if (v.size >= 2) c.getString(R.string.en_curve_note) else c.getString(R.string.en_curve_wait))
        }
    }
}
