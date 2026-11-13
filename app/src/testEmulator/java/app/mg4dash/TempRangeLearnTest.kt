package app.mg4dash

import app.mg4dash.car.CarBridge
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** SWI69 set point range learned from the car's answers (owner's design): it always wins once known. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TempRangeLearnTest {
    private fun learn(reported: Float?, sent: Float? = null, ago: Long = -1) = CarBridge.learnRange(reported, sent, ago)

    @Test fun valuesOnlyOneRangeHas() {
        assertEquals("LO of 16 to 30 as the car reports it", true, learn(15f))
        assertEquals(true, learn(15.5f))
        assertEquals(true, learn(16f))
        assertEquals("18 to 32 only", false, learn(32f))
        assertEquals(false, learn(32.5f))
        assertEquals(false, learn(33f))
        assertEquals("in both ranges: nothing learned", null, learn(22f))
        assertEquals("31 is HI on 16 to 30 and a number on 18 to 32", null, learn(31f))
        assertEquals("17 is a number on 16 to 30 and LO on 18 to 32", null, learn(17f))
    }

    @Test fun theCarsAnswerAtALimit() {
        assertEquals("LO 15.5 sent, the car kept 17.5: 18 to 32", false, learn(17.5f, 15.5f, 2_000))
        assertEquals("32.5 sent, the car kept 31: 16 to 30", true, learn(31f, 32.5f, 2_000))
        assertEquals("32 sent, the car kept 30.5", true, learn(30.5f, 32f, 2_000))
        assertEquals("too soon: the car has not answered yet", null, learn(17.5f, 15.5f, 500))
        assertEquals("too late: another change since", null, learn(17.5f, 15.5f, 10_000))
        assertEquals("not a limit", null, learn(22f, 22f, 2_000))
    }
}
