package app.mg4dash.cards

import android.content.Context
import app.mg4dash.radar.RadarCard
import app.mg4dash.ui.ConsumptionCard
import app.mg4dash.ui.DrivingCard
import app.mg4dash.ui.HomeSlots.Card
import app.mg4dash.ui.NavCard
import app.mg4dash.ui.TripsCard
import app.mg4dash.ui.WeatherCard

/**
 * The cards built through the [HomeCard] contract: one line each. A new card adds its entry to [HomeSlots.Card]
 * (id, name, icon, big, manual) and its line here; nothing else of the home screen changes.
 *
 * The cards missing here are still drawn by the home screen itself ([HostCard], exception: climate, energy, trip,
 * power, phone, shortcuts, map, web app): it builds them from its layout and lists them with these.
 */
object HomeCards {
    val factories: Map<Card, (Context) -> HomeCard> = linkedMapOf(
        Card.CONSUMPTION to ::ConsumptionCard,
        Card.HOURLY to { c -> WeatherCard(c, daily = false) },
        Card.DAILY to { c -> WeatherCard(c, daily = true) },
        Card.TRIPS to ::TripsCard,
        Card.DRIVING to ::DrivingCard,
        Card.RADAR to ::RadarCard,
        Card.NAV to ::NavCard,
    )

    fun create(card: Card, context: Context): HomeCard? = factories[card]?.invoke(context)
}
