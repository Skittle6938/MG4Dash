package app.mg4dash.cards

import app.mg4dash.system.Profiles
import app.mg4dash.ui.HomeSlots.Card
import app.mg4dash.ui.HomeSlots.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The card contract: its list, the common shape of the places, the cards' own settings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HomeCardsTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @Test fun eachCardIsTheOneItIsListedFor() {
        HomeCards.factories.forEach { (card, make) ->
            val h = make(ctx)
            assertEquals(card, h.card)
            // Shown and hidden twice in a row: harmless
            h.shown(true); h.shown(true); h.shown(false); h.shown(false)
        }
    }

    @Test fun commonShapeOfThePlaces() {
        assertTrue(CardPlace(Size.SMALL).small)
        assertTrue(CardPlace(Size.QUARTER).small)            // Grand affichage: the closest normal shape
        assertTrue(CardPlace(Size.QUARTER).big)
        assertTrue(CardPlace(Size.MEDIUM).medium)
        assertTrue(CardPlace(Size.TALL).tall)
        assertTrue(CardPlace(Size.HALF).tall)
        assertTrue(CardPlace(Size.LARGE).tall && CardPlace(Size.LARGE).wide)
        assertTrue(CardPlace(Size.WIDE).wide && !CardPlace(Size.WIDE).tall)
        assertTrue(CardPlace(Size.BAND).wide)
        assertFalse(CardPlace(Size.MEDIUM).big)
    }

    @Test fun cardSettingsFollowTheProfile() {
        val p = CardPrefs(ctx, Card.TRIPS)
        assertEquals("card_trips_show", p.key("show"))
        assertTrue(Profiles.classified(p.key("show")))
        p.setBool("show", false)
        assertFalse(CardPrefs(ctx, Card.TRIPS).bool("show", true))
    }

    @Test fun flagsKept() {
        assertEquals(setOf(Card.MAP, Card.CONSUMPTION, Card.SHORTCUTS, Card.WEB, Card.DRIVING, Card.RADAR, Card.NAV, Card.CHRONO, Card.LYRICS, Card.MEMO), Card.entries.filter { it.big }.toSet())
        assertEquals(setOf(Card.TRIPS, Card.DRIVING, Card.CLIMATE, Card.RADAR, Card.NAV, Card.CHRONO, Card.LYRICS, Card.MEMO), Card.entries.filter { it.manual }.toSet())
    }
}
