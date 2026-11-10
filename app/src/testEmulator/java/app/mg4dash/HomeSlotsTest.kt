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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class HomeSlotsTest {
    private val prefs get() = Prefs(RuntimeEnvironment.getApplication())

    @Test fun defaultsAreTheHomeScreenAsBefore() {
        val full = HomeSlots.load(prefs, Layout.FULL)
        assertEquals("tl=climate;bl=energy;tc=map,consumption,shortcuts;bc=hourly,daily;tr=media;br=trip,power,phone", full.encode())
        val map = HomeSlots.load(prefs, Layout.MAP)
        assertEquals("tl=hourly,daily;bl=climate;tc=energy,trip,power,phone;bc=media;r=map,consumption,shortcuts", map.encode())
        assertNull(full.slotOf(Card.WEB))
    }

    @Test fun choicePerWidgetIsKept() {
        prefs.raw.edit().putString("wc_map", "map,web").putString("wc_weather", "daily").putString("wc_stack", "energy,phone")
            .putString("wc_bottom", "power").putBoolean("shortcuts_page", false).commit()
        assertEquals("tl=daily;bl=climate;tc=energy,phone;bc=media;r=map,web", HomeSlots.load(prefs, Layout.MAP).encode())
        assertEquals("tl=climate;bl=energy;tc=map,web;bc=daily;tr=media;br=power", HomeSlots.load(prefs, Layout.FULL).encode())
    }

    @Test fun shortcutsPageSettingIsKept() {
        prefs.shortcutsPage = false
        assertEquals(listOf(Card.MAP, Card.CONSUMPTION), HomeSlots.load(prefs, Layout.FULL).cards("tc"))
    }

    @Test fun aCardTakenElsewhereLeavesItsPlace() {
        val c = HomeSlots.defaults(Layout.FULL)
        val moved = HomeSlots.toggle(c, "tc", Card.TRIP)
        assertNull(moved.refused)
        assertEquals(listOf(Card.MAP, Card.CONSUMPTION, Card.SHORTCUTS, Card.TRIP), moved.config.cards("tc"))
        assertEquals(listOf(Card.POWER, Card.PHONE), moved.config.cards("br"))
        // Touched again: it leaves, and is nowhere
        val off = HomeSlots.toggle(moved.config, "tc", Card.TRIP).config
        assertNull(off.slotOf(Card.TRIP))
    }

    @Test fun eachPlaceKeepsACard() {
        val c = HomeSlots.defaults(Layout.FULL)
        assertEquals(Refusal.KEEP_ONE, HomeSlots.toggle(c, "bl", Card.ENERGY).refused)
        assertEquals(Refusal.ONLY_CARD_THERE, HomeSlots.toggle(c, "br", Card.ENERGY).refused)
        // Power shown in the energy card (#29): the power left alone in a place does not count
        val power = setOf(Card.POWER)
        val noTrip = HomeSlots.toggle(c, "tc", Card.TRIP).config
        assertEquals(Refusal.ONLY_CARD_THERE, HomeSlots.toggle(noTrip, "tc", Card.PHONE, power).refused)
        assertNull(HomeSlots.toggle(noTrip, "tc", Card.PHONE).refused)
        // Alone there, it gets another card: the one left out
        val alone = HomeSlots.toggle(noTrip, "tc", Card.PHONE).config
        assertEquals(listOf(Card.POWER, Card.WEB), HomeSlots.fillEmpty(alone, power).cards("br"))
    }

    @Test fun mediaSwapsWithTheCardsOfItsNewPlace() {
        val c = HomeSlots.defaults(Layout.FULL)
        val moved = HomeSlots.setKind(c, "tc", Kind.MEDIA).config
        assertEquals(Fill.Media, moved.fill("tc"))
        assertEquals("the cards of its new place go where it was", listOf(Card.MAP, Card.CONSUMPTION, Card.SHORTCUTS), moved.cards("tr"))
        assertEquals(listOf(Card.CLIMATE), moved.cards("tl"))
    }

    @Test fun mediaAloneInItsPlace() {
        val c = HomeSlots.defaults(Layout.FULL)
        assertEquals(Refusal.MEDIA_ALONE, HomeSlots.toggle(c, "tr", Card.MAP).refused)
        // Taken off: the place starts with the card left out (the web app)
        val off = HomeSlots.setKind(c, "tr", Kind.CARDS).config
        assertEquals(listOf(Card.WEB), off.cards("tr"))
        assertNull(off.slotOf(Fill.Media))
    }

    @Test fun climateIsACardSwipedWithTheOthersInTheOrderTicked() {
        val c = HomeSlots.toggle(HomeSlots.defaults(Layout.FULL), "tl", Card.WEB).config
        assertEquals(listOf(Card.CLIMATE, Card.WEB), c.cards("tl"))
        val moved = HomeSlots.toggle(c, "tc", Card.CLIMATE).config
        assertEquals("added last: shown after the others", listOf(Card.MAP, Card.CONSUMPTION, Card.SHORTCUTS, Card.CLIMATE), moved.cards("tc"))
        assertEquals(listOf(Card.WEB), moved.cards("tl"))
    }

    @Test fun smallPlacesTakeNoClimateNorMedia() {
        val c = HomeSlots.defaults(Layout.MAP)
        assertEquals(Refusal.TOO_BIG, HomeSlots.toggle(c, "tl", Card.CLIMATE).refused)
        assertEquals(c, HomeSlots.setKind(c, "tl", Kind.MEDIA).config)
        // A tall place takes the media (owner's choice)
        assertEquals(Fill.Media, HomeSlots.setKind(c, "r", Kind.MEDIA).config.fill("r"))
    }

    @Test fun storedChoiceIsCleanedUp() {
        prefs.raw.edit().putString("slots_full", "tl=media;tc=map,map,trip;tr=media;bl=climate;bc=hourly;br=trip").commit()
        val c = HomeSlots.load(prefs, Layout.FULL)
        assertEquals(Fill.Media, c.fill("tl"))
        // Media twice, the trip twice: left out; the climate stored in a small place moves to a medium one; empty places get a card
        assertEquals(listOf(Card.MAP, Card.TRIP, Card.CLIMATE), c.cards("tc"))
        assertEquals(1, c.cards("tr").size)
        assertEquals(1, c.cards("bl").size)
        assertEquals(1, c.cards("br").size)
    }

    @Test fun resetGivesTheDefaults() {
        val p = prefs
        HomeSlots.save(p, HomeSlots.setKind(HomeSlots.defaults(Layout.MAP), "bl", Kind.MEDIA).config)
        HomeSlots.setCurrent(p, Layout.MAP, "r", Card.SHORTCUTS)
        HomeSlots.reset(p, Layout.MAP)
        assertEquals(HomeSlots.defaults(Layout.MAP), HomeSlots.load(p, Layout.MAP))
        assertEquals(Card.MAP, HomeSlots.current(p, Layout.MAP, "r", listOf(Card.MAP, Card.CONSUMPTION, Card.SHORTCUTS)))
    }

    @Test fun cardShownFollowsThePagesOfBefore() {
        prefs.raw.edit().putInt("home_widget", 2).putInt("secondary_widget", 1).putInt("weather_page", 1).commit()
        val full = HomeSlots.defaults(Layout.FULL)
        assertEquals(Card.CONSUMPTION, HomeSlots.current(prefs, Layout.FULL, "tc", full.cards("tc")))
        assertEquals(Card.POWER, HomeSlots.current(prefs, Layout.FULL, "br", full.cards("br")))
        assertEquals(Card.DAILY, HomeSlots.current(prefs, Layout.FULL, "bc", full.cards("bc")))
        HomeSlots.setCurrent(prefs, Layout.FULL, "tc", Card.SHORTCUTS)
        assertEquals(Card.SHORTCUTS, HomeSlots.current(prefs, Layout.FULL, "tc", full.cards("tc")))
        assertEquals(Card.MAP, HomeSlots.next(full.cards("tc"), Card.SHORTCUTS, 1))
        assertEquals(Card.CONSUMPTION, HomeSlots.next(full.cards("tc"), Card.SHORTCUTS, -1))
    }
}
