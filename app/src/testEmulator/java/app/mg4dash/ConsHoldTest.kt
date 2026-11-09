package app.mg4dash

import app.mg4dash.energy.ConsHold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The energy card's kWh/100 changes only by 0.5 or more, or after a minute (#29). */
class ConsHoldTest {
    @Test fun smallChangeKeepsTheShownValue() = assertEquals(15.2f, ConsHold.held(15.2f, 15.6f))

    @Test fun halfAKwhOrMoreReplacesIt() {
        assertEquals(15.7f, ConsHold.held(15.2f, 15.7f))
        assertEquals(14.6f, ConsHold.held(15.2f, 14.6f))
    }

    @Test fun aValueAppearingShowsAtOnce() = assertEquals(18.3f, ConsHold.held(null, 18.3f))

    @Test fun aSmallChangeShowsAfterAMinute() {
        assertEquals(15.2f, ConsHold.held(15.2f, 15.6f, 59_999L))
        assertEquals(15.6f, ConsHold.held(15.2f, 15.6f, 60_000L))
    }

    @Test fun anUnknownValueClearsIt() = assertNull(ConsHold.held(15.2f, null))
}
