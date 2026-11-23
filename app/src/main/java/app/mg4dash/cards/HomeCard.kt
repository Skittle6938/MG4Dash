package app.mg4dash.cards

import android.view.View
import app.mg4dash.ui.HomeSlots
import app.mg4dash.ui.OptionsPopup

/**
 * The contract of a home screen card (a widget placed in a place of the home screen). Every new card implements it,
 * in its own file, and is listed once in [HomeCards]: the home screen then builds it, gives it its place, shows and
 * hides it, and opens its options on a long press. See CONTRIBUTING.md › "Adding a home card".
 *
 * Rules for a card:
 * - Look: only the theme's colours ([CardKit], `Themes.color`), never a fixed colour: light and dark work by themselves.
 * - Size: it adapts to [place]; it never measures the screen itself.
 * - Life: it reads and refreshes its data only while [shown] is true (battery, mobile data).
 * - Settings: through [CardPrefs] (keys "card_<id>_…"), shown in [options]; texts in i18n/strings.psv.
 * - No data yet: a short message ([CardKit.empty]) instead of an empty card.
 */
interface HomeCard {
    /** Its entry in the list of cards (identity: id, name, icon, the places it accepts). */
    val card: HomeSlots.Card

    /** Its view, filling its place; the home screen wraps it in the cards' frame (corners, outline). */
    val view: View

    /**
     * Wrapped in the cards' frame (rounded corners, outline drawn over it). False only for a card that draws its own
     * panel (exception: the weather cards, a touchable tile).
     */
    val framed: Boolean get() = true

    /** The place it is in now: called before it is first shown, and again when it moves. */
    fun place(p: CardPlace) {}

    /**
     * Shown on screen (true) or not (false: another card of its place, the home screen out of sight, left out).
     * Called again with the same value is harmless. Shown: show the data at once, then follow it; hidden: stop.
     */
    fun shown(on: Boolean) {}

    /** Its options, added to the standard popup opened by a long press on the card; null: no options, no long press. */
    fun options(popup: OptionsPopup): OptionsPopup? = null

    /** Its options window closed: apply what changed (rebuild what is built with them). */
    fun optionsClosed() {}

    /**
     * Stopped ([shown] false) while home is out of sight (a page or another app over it). Only the cards drawn by the
     * home screen itself ([HostCard]) keep running.
     */
    val pausesWithHome: Boolean get() = true
}

/**
 * The place a card is in, in one common shape. [size] is the exact place (Grand affichage's ones included); a card
 * normally only reads the shape: [small], [medium], [tall], [wide] (two columns), and [big] for Grand affichage's
 * places, which already get bigger text by themselves.
 */
data class CardPlace(val size: HomeSlots.Size) {
    /** The normal place of the same shape (Grand affichage's places map to the closest one). */
    val form: HomeSlots.Size get() = size.form
    /** Small place (one column, 40 % of the height). */
    val small: Boolean get() = form == HomeSlots.Size.SMALL
    /** Medium place (one column, 60 % of the height). */
    val medium: Boolean get() = form == HomeSlots.Size.MEDIUM
    /** Full height (a tall place, or the big square of two columns). */
    val tall: Boolean get() = form == HomeSlots.Size.TALL || form == HomeSlots.Size.LARGE
    /** Two columns wide (the wide band or the big square). */
    val wide: Boolean get() = form.span == 2
    /** A place of Grand affichage. */
    val big: Boolean get() = size.big
}
