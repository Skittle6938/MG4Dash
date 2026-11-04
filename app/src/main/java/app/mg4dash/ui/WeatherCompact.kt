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
 * Map layout weather, widget style (issue #6):
 *  - on the left, the icon and temperature, then "min~max · condition";
 *  - on the right, the date, the time in large and the city;
 *  - at the bottom, a stack: hourly or daily forecast (swipe).
 * With no weather data (no connection): the date, time and the car's outside temperature.
 */
class WeatherCompact(context: Context) : LinearLayout(context) {
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
    /** Wind and chance of rain, on their own line (#34). */
    private val sub2 = tv(18f, R.color.text_sub)
    private val date = tv(18f, R.color.text_sub).apply { gravity = Gravity.END }
    private val time = tv(50f, R.color.text_value, bold = true).apply { gravity = Gravity.END }
    private val city = tv(18f, R.color.text_sub).apply { gravity = Gravity.END }
    private val cityLine = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
    private val forecast = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val right = LinearLayout(context)

    /** Bottom bar (#88): smaller weather and forecast, the wind / rain line was cut. */
    var tight = false

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
        right.apply { orientation = VERTICAL; gravity = Gravity.END }
        right.addView(date); right.addView(time); right.addView(city)
        top.addView(right, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        // Connection icons at the very top right (#86), the city under the clock (#18)
        addView(cityLine, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(top, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(forecast, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        tick()
    }

    /** Left margin of the content, under the city: room for the fog light button. */
    fun setLeftInset(px: Int) = top.setPadding(px, 0, 0, 0)

    /** Connection icons (#86), at the top right. */
    fun addConnections(row: android.view.View) = cityLine.addView(row)

    private fun setTempMargin(px: Int) {
        (temp.layoutParams as LayoutParams).let { if (it.leftMargin != px) { it.leftMargin = px; temp.layoutParams = it } }
    }

    private val ticker = Runnable { tick() }

    private fun tick() {
        val now = Date()
        time.text = app.mg4dash.system.Clock.hm(context, now)
        date.text = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(now).trimEnd('.').replaceFirstChar { it.uppercase() }
        removeCallbacks(ticker)
        postDelayed(ticker, 60_000L - System.currentTimeMillis() % 60_000L)
    }

    /** [page]: 0 = hourly, 1 = daily. */
    fun show(w: WeatherRepo.Weather?, outside: Float?, place: String, page: Int) {
        tick()
        city.put(w?.place ?: place)
        if (w == null) {
            // Offline (#18): time and outside temperature in large, centred in the whole widget
            icon.visibility = GONE
            // Without an icon, more margin: the temperature aligns left with "Outside" (#18)
            setTempMargin(0)
            temp.put(outside?.let { "${Math.round(it)}°" } ?: "—")
            temp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 72f)
            time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 72f)
            sub.put(context.getString(R.string.weather_outside_short))
            sub2.visibility = GONE
            top.gravity = Gravity.CENTER
            right.setPadding(0, 0, 0, 0)
            if (forecast.childCount > 0) forecast.removeAllViews()
            forecast.visibility = GONE
            return
        }
        icon.visibility = VISIBLE
        setTempMargin((10 * d).toInt())
        // Icon and temperature at the time's height; the block moves up for the wind / rain line (#34)
        val mainSp = if (tight) 44f else 50f
        temp.setTextSize(TypedValue.COMPLEX_UNIT_SP, mainSp)
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, mainSp)
        (icon.layoutParams as LayoutParams).let { lp ->
            val s = ((if (tight) 44 else 50) * d).toInt()
            if (lp.width != s) { lp.width = s; lp.height = s; icon.layoutParams = lp }
        }
        top.gravity = Gravity.TOP
        right.setPadding(0, 0, 0, 0)
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
        forecast.visibility = VISIBLE
        val items = if (page == 0) w.hours.map { Triple(app.mg4dash.system.Clock.hourLabel(context, it.label), it.code, "${it.tempC}°") }
            else w.days.map { Triple(it.label, it.code, "${it.minC}/${it.maxC}°") }
        forecast.removeAllViews()
        items.forEach { (label, code, value) ->
            val cell = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER }
            cell.addView(tv(if (tight) 14f else 16f, R.color.text_title).apply { text = label; gravity = Gravity.CENTER })
            val iconPx = ((if (tight) 22 else 26) * d).toInt()
            cell.addView(ImageView(context).apply { setImageResource(WeatherRepo.icon(code)); setColorFilter(c(WeatherRepo.iconColor(code))) },
                LayoutParams(iconPx, iconPx).apply { topMargin = ((if (tight) 1 else 2) * d).toInt(); bottomMargin = ((if (tight) 1 else 2) * d).toInt() })
            cell.addView(tv(if (tight) 17f else 19f, R.color.text_value).apply { text = value; gravity = Gravity.CENTER })
            forecast.addView(cell, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
    }

}
