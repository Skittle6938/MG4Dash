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
        val models = MODELS.map { a.getString(it.second) }
        return popup
            .choice(a.getString(R.string.st_wx_model, "").trimEnd(' ', ':', '\u00a0', '\u202f'), a.getString(R.string.wx_model_desc), models,
                { MODELS.indexOfFirst { it.first == prefs.weatherModel }.coerceAtLeast(0) }) { i ->
                prefs.weatherModel = MODELS[i].first; WeatherRepo.refreshNow(a)
            }
            // The city used without a position (24/11): the car's position now, or one searched by its name
            .choice(a.getString(R.string.st_wx_city), { a.getString(R.string.st_wx_city_desc, prefs.weatherCity) },
                { listOf(prefs.weatherCity, a.getString(R.string.wx_city_here), a.getString(R.string.wx_city_search)) }, { 0 }) { i ->
                when (i) {
                    1 -> {
                        val loc = app.mg4dash.map.LocationRepo.location.value
                        if (loc == null) Toasts.show(a, R.string.st_no_location, long = true)
                        else {
                            prefs.weatherLat = loc.latitude.toFloat(); prefs.weatherLon = loc.longitude.toFloat()
                            prefs.weatherCity = WeatherRepo.weather.value?.place?.takeIf { it.isNotBlank() } ?: a.getString(R.string.weather_here)
                            WeatherRepo.refreshNow(a); popup.refresh()
                        }
                    }
                    2 -> searchCity(a) { popup.refresh() }
                }
            }
    }

    /** A city searched by its name with the car's keyboard, then picked among the places found. */
    private fun searchCity(a: android.app.Activity, found: () -> Unit) {
        Ask.show(a, a.getString(R.string.wx_city_search), "", secret = false) { query ->
            if (query.isBlank()) return@show
            Thread {
                val found = app.mg4dash.weather.Geocoding.search(a, query)
                a.runOnUiThread {
                    when {
                        found == null -> Toasts.show(a, R.string.wx_city_offline, long = true)
                        found.isEmpty() -> Toasts.show(a, a.getString(R.string.wx_city_none, query), long = true)
                        else -> Windows.pick(a, a.getString(R.string.st_wx_city), found.map { it.label }) { k ->
                            val p = found[k]
                            app.mg4dash.system.Prefs(a).apply { weatherCity = p.name; weatherLat = p.lat.toFloat(); weatherLon = p.lon.toFloat() }
                            WeatherRepo.refreshNow(a); found()
                        }
                    }
                }
            }.start()
        }
    }

    companion object {
        /** Forecast models (#67): another one can better match the weather seen, depending on the region. */
        /** Stored value (unchanged) to its name; Automatic first, then Météo-France (24/11). */
        val MODELS = listOf("" to R.string.wx_model_auto, "meteofrance_seamless" to R.string.wx_model_meteofrance, "ecmwf_ifs025" to R.string.wx_model_ecmwf,
            "icon_seamless" to R.string.wx_model_dwd, "ukmo_seamless" to R.string.wx_model_ukmo, "gfs_seamless" to R.string.wx_model_noaa, "gem_seamless" to R.string.wx_model_gem)
    }

    private fun render(w: WeatherRepo.Weather?) {
        val c = weather.context
        weather.show(w, CarBridge.state.value.outsideTemp, app.mg4dash.system.Prefs(c).weatherCity, if (daily) 1 else 0)
    }
}
