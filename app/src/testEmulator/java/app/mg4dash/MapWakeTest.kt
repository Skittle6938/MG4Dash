package app.mg4dash

import app.mg4dash.ui.HomeSlots.Card
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The map card rests and wakes as the web app and the apps (Marc, 14/11): no more two fingers to move it. */
class MapWakeTest {
    @Test fun theMapWaitsForATouchLikeTheWebApp() {
        assertTrue("web app", MainActivity.restsUntilTouched(Card.WEB))
        assertTrue("map or app of the App card", MainActivity.restsUntilTouched(Card.MAP))
        Card.values().filter { it != Card.WEB && it != Card.MAP }.forEach {
            assertFalse("$it: swiped and touched at once", MainActivity.restsUntilTouched(it))
        }
        assertFalse(MainActivity.restsUntilTouched(null))
    }
}
