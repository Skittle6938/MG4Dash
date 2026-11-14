package app.mg4dash

import app.mg4dash.car.CarBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #112: the SAIC climate page looked for when the car brings the climate back, cold start included. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ClimatePageWatchTest {
    private val now = 1_000_000L

    @Test fun climateAlreadyOnAtStart() {
        // After a long stop: MG4Dash's first reading is already "on", no turning on is seen
        assertEquals(CarBridge.START_WATCH_MS, CarBridge.climatePageWatch(null, true, now, 0L))
        assertNull(CarBridge.climatePageWatch(null, false, now, 0L))
        assertNull(CarBridge.climatePageWatch(null, null, now, 0L))
    }

    @Test fun turnedOnByTheCarNotByMg4Dash() {
        assertEquals(CarBridge.TURN_ON_WATCH_MS, CarBridge.climatePageWatch(false, true, now, 0L))
        assertNull("asked by MG4Dash itself", CarBridge.climatePageWatch(false, true, now, now - 1_000))
        assertNull("still on", CarBridge.climatePageWatch(true, true, now, 0L))
        assertNull("turned off", CarBridge.climatePageWatch(true, false, now, 0L))
        assertNull("unread", CarBridge.climatePageWatch(false, null, now, 0L))
    }
}
