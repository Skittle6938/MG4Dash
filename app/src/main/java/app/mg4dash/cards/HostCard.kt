package app.mg4dash.cards

import android.view.View
import app.mg4dash.ui.HomeSlots
import app.mg4dash.ui.OptionsPopup

/**
 * A card still drawn by the home screen itself (exception, see CONTRIBUTING.md › "Exceptions"): the climate, the
 * energy, trip, power and phone cards, the shortcuts, the map and the web app. Their views come from the home
 * screen's layout and share its state (embedded apps, floating windows, the power effects, the fog light button), so
 * moving them out would risk breaking them; they still go through the card list, the same life and the same options
 * window as the others.
 *
 * Unlike the other cards, they are not stopped when home goes out of sight ([pausesWithHome] false): the map's
 * embedded app or the web page must stay as they are behind a page opened over home.
 */
class HostCard(
    override val card: HomeSlots.Card,
    override val view: View,
    override val framed: Boolean,
    private val onShown: (Boolean) -> Unit = {},
    private val onOptions: ((OptionsPopup) -> OptionsPopup)? = null,
    private val onOptionsClosed: () -> Unit = {},
) : HomeCard {
    override val pausesWithHome: Boolean get() = false
    override fun shown(on: Boolean) = onShown(on)
    override fun options(popup: OptionsPopup): OptionsPopup? = onOptions?.invoke(popup)
    override fun optionsClosed() = onOptionsClosed()
}
