package app.mg4dash.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import app.mg4dash.R
import app.mg4dash.car.CarBridge
import app.mg4dash.cards.CardPlace
import app.mg4dash.cards.CardScope
import app.mg4dash.cards.HomeCard
import app.mg4dash.weather.WeatherRepo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The weather cards, widget style (issue #6): by hour ([daily] false) or by day, with the date, the time and the city.
 * A touch asks for the weather again. While shown: each new forecast, and with none (no connection) the date, the
 * time and the car's outside temperature every 5 s.
 */
class WeatherCard(context: Context, private val daily: Boolean) : HomeCard {
    override val card = if (daily) HomeSlots.Card.DAILY else HomeSlots.Card.HOURLY
    /** In the cards' frame like the others; it lights up while touched (a touch asks for the weather again). */
    override val view: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = context.resources.getDimensionPixelSize(R.dimen.panel_pad); setPadding(pad, pad, pad, pad)
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), android.graphics.drawable.GradientDrawable().apply {
                setColor(Themes.color(context, R.color.button_pressed)); cornerRadius = context.resources.getDimension(R.dimen.radius_panel)
            })
        }
        isClickable = true; isFocusable = true
    }

    /** Its content (the home screen moves it clear of MG's fog light button in the top left place). */
    val weather = WeatherCompact(context).apply { tight = !Dock.vertical(context) }

    init {
        (view as LinearLayout).addView(weather, LinearLayout.LayoutParams(-1, -1))
        view.setOnClickListener { WeatherRepo.refreshNow(context) }
    }

    /** Grand affichage: tight in a quarter or a band, full in a half. */
    override fun place(p: CardPlace) {
        if (!p.big) return
        val tight = p.size != HomeSlots.Size.HALF
        if (weather.tight != tight) { weather.tight = tight; render(WeatherRepo.shown.value) }
    }

    private val live = CardScope()
    override fun shown(on: Boolean) = live.set(on) {
        launch { WeatherRepo.shown.collect { render(it) } }
        launch { while (true) { delay(5_000); if (WeatherRepo.shown.value == null) render(null) } }
    }

    /** The forecast's model (#67) and the city used without a position (#7), once in Settings › Connections (24/11). */
    override fun options(popup: OptionsPopup): OptionsPopup {
        val a = popup.let { view.context as? android.app.Activity } ?: return popup
        val prefs = app.mg4dash.system.Prefs(a)
        val models = MODELS.map { if (it.first.isEmpty()) a.getString(R.string.st_wx_model_auto) else it.second }
        val here = a.getString(R.string.st_use_location)
        val cityLabels = CITIES.map { it.first } + here
        return popup
            .choice(a.getString(R.string.st_wx_model, "").trimEnd(' ', ':', '\u00a0', '\u202f'), null, models,
                { MODELS.indexOfFirst { it.first == prefs.weatherModel }.coerceAtLeast(0) }) { i ->
                prefs.weatherModel = MODELS[i].first; WeatherRepo.refreshNow(a)
            }
            .choice(a.getString(R.string.st_wx_city), a.getString(R.string.st_wx_city_desc, prefs.weatherCity), cityLabels,
                { CITIES.indexOfFirst { it.first == prefs.weatherCity }.let { if (it < 0) cityLabels.lastIndex else it } }) { i ->
                if (i < CITIES.size) {
                    val c = CITIES[i]; prefs.weatherCity = c.first; prefs.weatherLat = c.second; prefs.weatherLon = c.third
                    WeatherRepo.refreshNow(a)
                } else {
                    val loc = app.mg4dash.map.LocationRepo.location.value
                    if (loc == null) Toasts.show(a, R.string.st_no_location, long = true)
                    else {
                        prefs.weatherLat = loc.latitude.toFloat(); prefs.weatherLon = loc.longitude.toFloat()
                        prefs.weatherCity = WeatherRepo.weather.value?.place?.takeIf { it.isNotBlank() } ?: a.getString(R.string.weather_here)
                        WeatherRepo.refreshNow(a)
                    }
                }
            }
    }

    companion object {
        /** Forecast models (#67): another one can better match the weather seen, depending on the region. */
        val MODELS = listOf("" to "Auto", "ecmwf_ifs025" to "ECMWF", "icon_seamless" to "DWD ICON", "meteofrance_seamless" to "Météo-France",
            "ukmo_seamless" to "UK Met Office", "gfs_seamless" to "NOAA GFS", "gem_seamless" to "GEM (Canada)")
        /** Fallback cities when the car gives no position. */
        val CITIES = listOf(
            Triple("Paris", 48.8566f, 2.3522f), Triple("Lyon", 45.764f, 4.8357f),
            Triple("Marseille", 43.2965f, 5.3698f), Triple("Toulouse", 43.6047f, 1.4442f),
            Triple("Bordeaux", 44.8378f, -0.5792f), Triple("Lille", 50.6292f, 3.0573f),
            Triple("Nantes", 47.2184f, -1.5536f), Triple("Strasbourg", 48.5734f, 7.7521f),
            Triple("Nice", 43.7102f, 7.262f), Triple("Bruxelles", 50.8503f, 4.3517f),
            Triple("Genève", 46.2044f, 6.1432f),
        )
    }

    private fun render(w: WeatherRepo.Weather?) {
        val c = weather.context
        weather.show(w, CarBridge.state.value.outsideTemp, app.mg4dash.system.Prefs(c).weatherCity, if (daily) 1 else 0)
    }
}
