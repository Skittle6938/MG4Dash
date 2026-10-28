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
    private val bar = StackedBar(context)
    private val legend = LinearLayout(context).apply { orientation = VERTICAL }
    private val regen = tv(17f, R.color.accent_energy)
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
        addView(total)
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, (22 * d).toInt()).apply { topMargin = (6 * d).toInt() })
        addView(legend, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * d).toInt() })
        addView(regen)
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
        total.text = if (ok) String.format(Locale.getDefault(), "%.1f kWh", t.outKwh) else "—"
        val green = c(R.color.accent_energy); val cyan = c(R.color.accent_cold); val orange = c(R.color.accent_heat)
        bar.parts = if (ok) listOf(StackedBar.Part(t.driveKwh, green), StackedBar.Part(t.climKwh, cyan), StackedBar.Part(t.accKwh, orange)) else emptyList()
        legend.removeAllViews()
        listOf(Triple(R.string.en_drive, t.driveKwh, green), Triple(R.string.en_climate, t.climKwh, cyan), Triple(R.string.en_accessories, t.accKwh, orange))
            .forEach { (label, v, col) ->
                val r = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                r.addView(View(context).apply { setBackgroundColor(col) }, LayoutParams((12 * d).toInt(), (12 * d).toInt()))
                r.addView(tv(17f, R.color.text_sub).apply { text = context.getString(label) },
                    LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = (10 * d).toInt() })
                r.addView(tv(17f, R.color.text_value).apply {
                    text = if (ok) String.format(Locale.getDefault(), "%.1f kWh · %d %%", v, Math.round(v / t.outKwh * 100)) else "—"
                })
                legend.addView(r, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = (3 * d).toInt() })
            }
        regen.text = if (t.regenKwh > 0f) context.getString(R.string.en_regen, String.format(Locale.getDefault(), "%.1f", t.regenKwh)) else ""
        val (values, n) = curve(context, period)
        chart.values = values
        chart.emptyText = if (period == 2) R.string.chart_empty_trips else R.string.chart_empty
        note.text = n
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
         * Courbe et légende de la période (#44) : un point par km du trajet ou depuis la recharge
         * (moyenne glissante sur 3 points), ou un point par trajet sur 12 mois.
         */
        fun curve(c: android.content.Context, period: Int): Pair<List<Float>, String> {
            val raw = app.mg4dash.energy.ConsumptionCurve.curves.value.of(period)
            val v = raw.map { app.mg4dash.system.Units.cons(c, it) }
            // Moyenne sans sa valeur dans la légende : l'étiquette de la ligne pointillée la donne déjà (#44)
            return if (period == 2) v to (if (v.size >= 2) c.getString(R.string.en_trips_note, v.size) else c.getString(R.string.en_trips_wait))
            else v.smooth3() to (if (v.size >= 2) c.getString(R.string.en_curve_note) else c.getString(R.string.en_curve_wait))
        }
    }
}
