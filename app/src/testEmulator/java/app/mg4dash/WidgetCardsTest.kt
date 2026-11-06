package app.mg4dash

import app.mg4dash.system.Prefs
import app.mg4dash.ui.WidgetCards
import app.mg4dash.ui.WidgetCards.Card
import app.mg4dash.ui.WidgetCards.Widget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class WidgetCardsTest {
    private val prefs get() = Prefs(RuntimeEnvironment.getApplication())

    @Test fun defaultsLeaveTheWebAppOff() {
        assertEquals(listOf(Card.MAP, Card.CONSUMPTION, Card.SHORTCUTS), WidgetCards.on(prefs, Widget.MAP))
        assertEquals(listOf(0, 2, 1), WidgetCards.pages(prefs, Widget.MAP))
        assertEquals(4, WidgetCards.on(prefs, Widget.STACK).size)
    }

    @Test fun shortcutsFollowTheirSetting() {
        val p = prefs
        p.shortcutsPage = false
        assertEquals(listOf(Card.MAP, Card.CONSUMPTION), WidgetCards.on(p, Widget.MAP))
        WidgetCards.toggle(p, Widget.MAP, Card.SHORTCUTS)
        assertTrue(p.shortcutsPage)
        assertTrue(WidgetCards.isOn(p, Widget.MAP, Card.SHORTCUTS))
    }

    @Test fun webAppAndOrder() {
        val p = prefs
        WidgetCards.toggle(p, Widget.MAP, Card.WEB)
        assertEquals(listOf(0, 2, 1, 3), WidgetCards.pages(p, Widget.MAP))
        assertEquals(3, WidgetCards.next(p, Widget.MAP, 1, 1))
        assertEquals(0, WidgetCards.next(p, Widget.MAP, 3, 1))
        assertEquals(1, WidgetCards.next(p, Widget.MAP, 3, -1))
    }

    @Test fun theLastCardStays() {
        val p = prefs
        assertTrue(WidgetCards.toggle(p, Widget.WEATHER, Card.DAILY))
        assertFalse(WidgetCards.toggle(p, Widget.WEATHER, Card.HOURLY))
        assertEquals(listOf(Card.HOURLY), WidgetCards.on(p, Widget.WEATHER))
        assertEquals(0, WidgetCards.next(p, Widget.WEATHER, 0, 1))
        assertEquals(0, WidgetCards.valid(p, Widget.WEATHER, 1))
    }

    @Test fun skippedPagesAndCardsOff() {
        val p = prefs
        WidgetCards.toggle(p, Widget.STACK, Card.TRIP)
        // energy → power → phone; with the power page in the energy card: energy → phone
        assertEquals(2, WidgetCards.next(p, Widget.STACK, 0, 1))
        assertEquals(3, WidgetCards.next(p, Widget.STACK, 0, 1, setOf(2)))
        assertEquals(0, WidgetCards.valid(p, Widget.STACK, 1))
    }
}
