package app.mg4dash.ui

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.mg4dash.R
import app.mg4dash.weather.WeatherRepo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Météo du style Carte, façon widget (issue #6) :
 *  - à gauche, l'icône et la température, puis « min~max · état » ;
 *  - à droite, la date, l'heure en grand et la ville ;
 *  - en bas, une pile : prévisions par heure ou par jour (balayer ou toucher les points).
 * Sans données météo (pas de connexion) : la date, l'heure et la température extérieure de la voiture.
 */
class WeatherCompact(context: Context, private val onPage: () -> Unit) : LinearLayout(context) {
    private val d = resources.displayMetrics.density
    private fun c(res: Int) = Themes.color(context, res)
    private fun tv(sp: Float, colorRes: Int, bold: Boolean = false) = TextView(context).apply {
        setTextColor(c(colorRes)); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); maxLines = 1
        includeFontPadding = false
        if (bold) typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }

    private val icon = ImageView(context)
    private val top = LinearLayout(context)
    private val temp = tv(52f, R.color.text_value, bold = true)
    private val sub = tv(18f, R.color.text_sub)
    /** Vent et risque de pluie, sur leur propre ligne (#34). */
    private val sub2 = tv(18f, R.color.text_sub)
    private val date = tv(18f, R.color.text_sub).apply { gravity = Gravity.END }
    private val time = tv(50f, R.color.text_value, bold = true).apply { gravity = Gravity.END }
    private val city = tv(18f, R.color.text_sub).apply { gravity = Gravity.END }
    private val forecast = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val dots = tv(15f, R.color.text_title).apply {
        letterSpacing = 0.3f; gravity = Gravity.CENTER
        setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
        setOnClickListener { onPage() }
    }

    init {
        orientation = VERTICAL
        top.apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val left = LinearLayout(context).apply { orientation = VERTICAL }
        val tr = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tr.addView(icon, LayoutParams((50 * d).toInt(), (50 * d).toInt()))
        tr.addView(temp, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = (10 * d).toInt() })
        left.addView(tr)
        left.addView(sub, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = (4 * d).toInt() })
        left.addView(sub2, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = (2 * d).toInt() })
        top.addView(left, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val right = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.END }
        right.addView(date); right.addView(time); right.addView(city)
        top.addView(right, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(top, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(forecast, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(dots, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        tick()
    }

    private val ticker = Runnable { tick() }

    private fun tick() {
        val now = Date()
        time.text = app.mg4dash.system.Clock.hm(context, now)
        date.text = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(now).trimEnd('.').replaceFirstChar { it.uppercase() }
        removeCallbacks(ticker)
        postDelayed(ticker, 60_000L - System.currentTimeMillis() % 60_000L)
    }

    /** [page] : 0 = par heure, 1 = par jour. */
    fun show(w: WeatherRepo.Weather?, outside: Float?, place: String, page: Int) {
        tick()
        city.text = w?.place ?: place
        if (w == null) {
            // Hors connexion (#18) : heure et température extérieure en grand, centrées dans tout le widget
            icon.visibility = GONE
            temp.text = outside?.let { "${Math.round(it)}°" } ?: "—"
            temp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 72f)
            time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 72f)
            sub.text = context.getString(R.string.weather_outside_short)
            sub2.visibility = GONE
            top.gravity = Gravity.CENTER
            forecast.removeAllViews(); forecast.visibility = GONE; dots.visibility = GONE
            return
        }
        icon.visibility = VISIBLE
        // Icône et température à la hauteur de l'heure ; le bloc remonte pour la ligne vent / pluie (#34)
        temp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 50f)
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 50f)
        top.gravity = Gravity.TOP
        icon.setImageResource(WeatherRepo.icon(w.code))
        icon.setColorFilter(c(WeatherRepo.iconColor(w.code)))
        temp.text = "${w.tempC}°"
        sub.text = listOfNotNull(
            if (w.todayMin != null && w.todayMax != null) "${w.todayMin}~${w.todayMax}°" else null,
            WeatherRepo.label(w.code),
        ).joinToString("  ·  ")
        sub2.visibility = VISIBLE
        sub2.text = listOfNotNull(
            "${Math.round(app.mg4dash.system.Units.dist(context, w.windKmh.toFloat()))} ${app.mg4dash.system.Units.speedUnit(context)}",
            w.rainPct?.let { context.getString(R.string.weather_rain, it) },
        ).joinToString("  ·  ")
        forecast.visibility = VISIBLE; dots.visibility = VISIBLE
        dots.text = (0..1).joinToString(" ") { if (it == page) "●" else "○" }
        val items = if (page == 0) w.hours.map { Triple(app.mg4dash.system.Clock.hourLabel(context, it.label), it.code, "${it.tempC}°") }
            else w.days.map { Triple(it.label, it.code, "${it.minC}/${it.maxC}°") }
        forecast.removeAllViews()
        items.forEach { (label, code, value) ->
            val cell = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER }
            cell.addView(tv(16f, R.color.text_title).apply { text = label; gravity = Gravity.CENTER })
            cell.addView(ImageView(context).apply { setImageResource(WeatherRepo.icon(code)); setColorFilter(c(WeatherRepo.iconColor(code))) },
                LayoutParams((26 * d).toInt(), (26 * d).toInt()).apply { topMargin = (2 * d).toInt(); bottomMargin = (2 * d).toInt() })
            cell.addView(tv(19f, R.color.text_value).apply { text = value; gravity = Gravity.CENTER })
            forecast.addView(cell, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
    }
}
