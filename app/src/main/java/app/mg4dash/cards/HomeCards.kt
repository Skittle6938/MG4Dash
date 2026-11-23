package app.mg4dash.cards

import android.content.Context
import app.mg4dash.ui.DrivingCard
import app.mg4dash.ui.HomeSlots.Card
import app.mg4dash.ui.TripsCard

/**
 * The cards built through the [HomeCard] contract: one line each. A new card adds its entry to [HomeSlots.Card]
 * (id, name, icon, big, manual) and its line here; nothing else of the home screen changes.
 *
 * The other cards are still built by the home screen itself, as before (to be moved here one by one).
 */
object HomeCards {
    val factories: Map<Card, (Context) -> HomeCard> = linkedMapOf(
        Card.TRIPS to ::TripsCard,
        Card.DRIVING to ::DrivingCard,
    )

    fun create(card: Card, context: Context): HomeCard? = factories[card]?.invoke(context)
}
