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
    private val chart = LineChart(context)
    private val note = tv(15f, R.color.text_sub)

    private val prefs = app.mg4dash.system.Prefs(context)
    private val periodLabels = listOf(R.string.en_since_charge, R.string.en_trip, R.string.en_last_year).map { context.getString(it) }
    private var lastHistory: List<Float> = emptyList()
    private val periodTv: TextView =
        tv(19f, R.color.text_value).apply {
            setPadding(0, (6 * d).toInt(), (24 * d).toInt(), (6 * d).toInt())
            isClickable = true
            setOnClickListener { v ->
                (context as? android.app.Activity)?.let { a ->
                    SourceMenu.choose(a, v, periodLabels, prefs.consumptionPeriod) { prefs.consumptionPeriod = it; show(lastHistory) }
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

    fun show(history: List<Float>) {
        lastHistory = history
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
        // 12 derniers mois : une valeur par mois, pour voir l'effet des saisons
        if (period == 2) {
            val m = EnergyMonitor.monthlyPer100().map { app.mg4dash.system.Units.cons(context, it) }
            chart.values = m
            note.text = if (m.size >= 2) context.getString(R.string.en_months_note, m.size,
                String.format(Locale.getDefault(), "%.1f", m.average())) else context.getString(R.string.en_months_wait)
        } else {
            val h = history.map { app.mg4dash.system.Units.cons(context, it) }
            chart.values = h.smooth3()
            note.text = if (h.size >= 2) context.getString(R.string.en_history_note, Math.round(app.mg4dash.system.Units.dist(context, h.size.toFloat())),
                String.format(Locale.getDefault(), "%.1f", h.average()), app.mg4dash.system.Units.distUnit(context)) else ""
        }
    }
}
