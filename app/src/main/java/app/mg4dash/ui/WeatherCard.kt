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
    override val view: View = LayoutInflater.from(context).inflate(R.layout.card_tile, null, false) as LinearLayout
    /** Exception: a touchable tile with its own panel, not in the cards' frame (as before the card contract). */
    override val framed get() = false

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

    private fun render(w: WeatherRepo.Weather?) {
        val c = weather.context
        weather.show(w, CarBridge.state.value.outsideTemp, app.mg4dash.system.Prefs(c).weatherCity, if (daily) 1 else 0)
    }
}
