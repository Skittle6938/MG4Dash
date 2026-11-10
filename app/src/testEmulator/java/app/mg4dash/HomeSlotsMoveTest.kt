package app.mg4dash

import app.mg4dash.system.Prefs
import app.mg4dash.ui.HomeSlots
import app.mg4dash.ui.HomeSlots.Card
import app.mg4dash.ui.HomeSlots.Fill
import app.mg4dash.ui.HomeSlots.Kind
import app.mg4dash.ui.HomeSlots.Layout
import app.mg4dash.ui.HomeSlots.Refusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Cards moved between the home screen's places in the edit mode (#97): rules that hold for any sequence of
 * changes, both layouts, cards that cannot be shown now, and what is kept.
 */
@RunWith(RobolectricTestRunner::class)
class HomeSlotsMoveTest {
    private val prefs get() = Prefs(RuntimeEnvironment.getApplication())

    /** A card in one place at most, every place of cards with one, the media once, alone, in a medium place. */
    private fun assertValid(c: HomeSlots.Config, unavailable: Set<Card> = emptySet()) {
        val all = c.layout.slots.flatMap { c.cards(it.id) }
        assertEquals("a card twice in $c", all.size, all.toSet().size)
        for (s in c.layout.slots) {
            val f = c.fill(s.id)
            if (f is Fill.Cards) assertTrue("place ${s.id} without a card in $c", f.cards.any { it !in unavailable })
            else assertTrue("${s.id} in $c", HomeSlots.mediaFits(s.size))
            if (f is Fill.Cards) f.cards.forEach { assertTrue("$it fits ${s.id}", HomeSlots.fits(it, s.size)) }
        }
        assertTrue(c.fills.values.count { it == Fill.Media } <= 1)
    }

    @Test fun defaultsAreValidAndRoundTrip() {
        for (l in Layout.entries) {
            val c = HomeSlots.defaults(l)
            assertValid(c)
            assertEquals(c, HomeSlots.decode(l, c.encode()))
        }
    }

    @Test fun everyCardMovedEverywhereKeepsTheRules() {
        // Each card touched in each place, in turn, from the defaults: whatever is accepted stays valid
        for (l in Layout.entries) {
            var c = HomeSlots.defaults(l)
            for (slot in l.slots) for (card in Card.entries) {
                val ch = HomeSlots.toggle(c, slot.id, card)
                if (ch.refused == null) c = ch.config else assertEquals("refused: unchanged", c, ch.config)
                assertValid(c)
            }
            // Saved and read again: the same
            HomeSlots.save(prefs, c)
            assertEquals(c, HomeSlots.load(prefs, l))
        }
    }

    @Test fun everyKindEverywhereKeepsTheRules() {
        for (l in Layout.entries) {
            var c = HomeSlots.defaults(l)
            for (kind in listOf(Kind.CARDS, Kind.MEDIA, Kind.CARDS, Kind.MEDIA)) for (slot in l.slots) {
                val ch = HomeSlots.setKind(c, slot.id, kind)
                if (ch.refused == null) c = ch.config
                assertValid(c)
            }
        }
    }

    @Test fun cardMovedBetweenPlacesOfTheMapLayout() {
        val c = HomeSlots.defaults(Layout.MAP)
        // The consumption leaves the map's place for the top left one
        val moved = HomeSlots.toggle(c, "tl", Card.CONSUMPTION).config
        assertEquals("added last, in the order ticked", listOf(Card.HOURLY, Card.DAILY, Card.CONSUMPTION), moved.cards("tl"))
        assertEquals(listOf(Card.MAP, Card.SHORTCUTS), moved.cards("r"))
        assertEquals("tl", moved.slotOf(Card.CONSUMPTION))
    }

    @Test fun webAppAddedThenRemoved() {
        val c = HomeSlots.defaults(Layout.FULL)
        assertNull(c.slotOf(Card.WEB))
        val on = HomeSlots.toggle(c, "tc", Card.WEB).config
        assertEquals(listOf(Card.MAP, Card.CONSUMPTION, Card.SHORTCUTS, Card.WEB), on.cards("tc"))
        val off = HomeSlots.toggle(on, "tc", Card.WEB).config
        assertEquals(c, off)
    }

    @Test fun climateOrMediaCannotGoToASmallOrTallPlace() {
        val full = HomeSlots.defaults(Layout.FULL)
        for (s in listOf("bl", "bc", "br")) {
            assertEquals(Refusal.TOO_BIG, HomeSlots.toggle(full, s, Card.CLIMATE).refused)
            assertEquals(full, HomeSlots.setKind(full, s, Kind.MEDIA).config)
        }
        // Stored by hand there: left out when read
        val c = HomeSlots.decode(Layout.FULL, "tl=map;tc=trip;tr=power;bl=media;bc=climate;br=phone")
        assertTrue(c.fill("bl") is Fill.Cards)
        assertTrue(c.fill("bc") is Fill.Cards)
    }

    @Test fun climateLeftOutThenBack() {
        val withWeb = HomeSlots.toggle(HomeSlots.defaults(Layout.FULL), "tl", Card.WEB).config
        val noClimate = HomeSlots.toggle(withWeb, "tl", Card.CLIMATE).config
        assertNull(noClimate.slotOf(Card.CLIMATE))
        val back = HomeSlots.toggle(noClimate, "tc", Card.CLIMATE).config
        assertEquals("tc", back.slotOf(Card.CLIMATE))
        assertValid(back)
    }

    @Test fun cardsRefusedWhenNoneCanBeTaken() {
        // All cards placed one per place, the others not available now: none to give to a new place of cards
        val c = HomeSlots.decode(Layout.FULL, "tl=climate;tc=map;tr=media;bl=energy;bc=hourly;br=trip")
        val unavailable = setOf(Card.CONSUMPTION, Card.SHORTCUTS, Card.WEB, Card.POWER, Card.PHONE, Card.DAILY)
        val ch = HomeSlots.setKind(c, "tr", Kind.CARDS, unavailable)
        assertEquals(Refusal.NO_CARD, ch.refused)
        assertEquals(c, ch.config)
    }

    @Test fun unavailableCardsDoNotCountForAPlace() {
        // The web app without an address: a place with only it gets another card
        prefs.raw.edit().putString("slots_full", "tl=climate;tc=web;tr=media;bl=energy;bc=hourly,daily;br=trip,power,phone").commit()
        val c = HomeSlots.load(prefs, Layout.FULL, setOf(Card.WEB))
        assertTrue(c.cards("tc").any { it != Card.WEB })
        assertValid(c, setOf(Card.WEB))
    }

    @Test fun decodeIgnoresUnknownAndBrokenEntries() {
        val c = HomeSlots.decode(Layout.FULL, "tl=climate;tc=map,radar,,trip;zz=phone;garbage;tr=media;bl=energy;bc=hourly;br=power")
        assertEquals(listOf(Card.MAP, Card.TRIP), c.cards("tc"))
        assertNull(c.slotOf(Card.PHONE))
        assertEquals(Fill.Media, c.fill("tr"))
    }

    @Test fun emptyStoredPlacesAreFilled() {
        prefs.raw.edit().putString("slots_map", "").commit()
        val c = HomeSlots.load(prefs, Layout.MAP)
        assertValid(c)
    }

    @Test fun layoutFollowsTheHomeStyle() {
        assertEquals(Layout.FULL, HomeSlots.layoutOf(prefs))
        prefs.homeStyle = 2
        assertEquals(Layout.MAP, HomeSlots.layoutOf(prefs))
        prefs.homeStyle = 1
        assertEquals(Layout.FULL, HomeSlots.layoutOf(prefs))
    }

    @Test fun layoutsAreKeptApart() {
        val full = HomeSlots.toggle(HomeSlots.defaults(Layout.FULL), "tc", Card.WEB).config
        HomeSlots.save(prefs, full)
        assertEquals(full, HomeSlots.load(prefs, Layout.FULL))
        assertEquals(HomeSlots.defaults(Layout.MAP), HomeSlots.load(prefs, Layout.MAP))
    }

    @Test fun cardShownIsKeptPerPlaceAndLayout() {
        HomeSlots.setCurrent(prefs, Layout.FULL, "tc", Card.SHORTCUTS)
        val cards = listOf(Card.MAP, Card.CONSUMPTION, Card.SHORTCUTS)
        assertEquals(Card.SHORTCUTS, HomeSlots.current(prefs, Layout.FULL, "tc", cards))
        assertEquals(Card.MAP, HomeSlots.current(prefs, Layout.MAP, "r", cards))
        // The card shown was moved elsewhere: the first of the place
        assertEquals(Card.MAP, HomeSlots.current(prefs, Layout.FULL, "tc", listOf(Card.MAP, Card.CONSUMPTION)))
        assertNull(HomeSlots.current(prefs, Layout.FULL, "tc", emptyList()))
    }

    @Test fun nextGoesRound() {
        val cards = listOf(Card.ENERGY, Card.TRIP, Card.POWER)
        assertEquals(Card.TRIP, HomeSlots.next(cards, Card.ENERGY, 1))
        assertEquals(Card.ENERGY, HomeSlots.next(cards, Card.POWER, 1))
        assertEquals(Card.POWER, HomeSlots.next(cards, Card.ENERGY, -1))
        assertEquals("unknown: the first", Card.ENERGY, HomeSlots.next(cards, Card.MAP, 1))
        assertNull(HomeSlots.next(emptyList(), Card.MAP, 1))
    }

    @Test fun kindOfEachFill() {
        assertEquals(Kind.MEDIA, HomeSlots.kindOf(Fill.Media))
        assertEquals(Kind.CARDS, HomeSlots.kindOf(Fill.Cards(listOf(Card.MAP))))
    }

    @Test fun cardIdsAreUnique() {
        val ids = Card.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
