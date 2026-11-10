package app.mg4dash

import android.content.Context
import android.os.Looper
import app.mg4dash.energy.TripLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Manual readings (#102): the card's button, their history, among the trips in the list, the filter, Clear. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1920dp-h720dp-land-mdpi")
class ReadingsTest {
    private val sp get() = RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", Context.MODE_PRIVATE)
    private val h = 3_600_000L
    private val t0 = 1_780_000_000_000L

    @Before fun empty() { sp.edit().clear().commit(); TripLog.load(sp) }

    private fun trip(start: Long, km0: Int, km: Float) = TripLog.Trip(start, start + h / 2, km0, km0 + Math.round(km), km, km * 0.15f, 90, 80)

    @Test fun cardButtonStartsAndStops() {
        val act = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        repeat(3) { shadowOf(Looper.getMainLooper()).idle() }
        val card = app.mg4dash.ui.TripsCard(act).apply { show() }
        // The simulated car gives its odometer
        val km = app.mg4dash.car.CarBridge.state.value.odometerKm
        assertNotNull(km)
        card.readingButton.performClick()
        assertEquals("started from the card", true, TripLog.reading(sp)?.running)
        assertTrue(card.readingButton.text.startsWith("■"))
        card.readingButton.performClick()
        assertEquals("stopped from the card", false, TripLog.reading(sp)?.running)
        assertEquals("into the history", 1, TripLog.readings(sp).size)
        assertTrue(card.readingButton.text.startsWith("●"))
        assertEquals("the button does not open the page", null, shadowOf(act).nextStartedActivity)
    }

    @Test fun fiftyKeptReadBack() {
        repeat(55) { i -> TripLog.startReading(sp, t0 + i * h, 1000 + i * 10); TripLog.stopReading(sp, t0 + i * h + h / 2, 1005 + i * 10) }
        val back = TripLog.readings(RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", Context.MODE_PRIVATE))
        assertEquals(TripLog.MAX, back.size)
        assertEquals("the latest first", 1000 + 54 * 10, back.first().startKm)
        assertEquals(1005 + 54 * 10, back.first().endKm)
    }

    @Test fun amongTheTripsByTheirEnd() {
        val trips = listOf(trip(t0 + 5 * h, 120, 10f), trip(t0 + 3 * h, 100, 20f), trip(t0 + h, 80, 20f)).sortedByDescending { it.start }
        val done = TripLog.Reading(t0 + 3 * h - 60_000, 100, t0 + 4 * h, 120)
        val running = TripLog.Reading(t0 + 5 * h - 60_000, 120)
        val all = TripLog.entries(trips, null, running, listOf(done), t0 + 6 * h, TripLog.Filter.ALL)
        assertTrue("the reading in progress first", (all[0] as TripLog.Entry.OfReading).reading.running)
        assertEquals("then the trip of 5 h", t0 + 5 * h, (all[1] as TripLog.Entry.OfTrip).trip.start)
        assertEquals("the finished reading, at its end, above the trip it covers", done, (all[2] as TripLog.Entry.OfReading).reading)
        assertEquals(t0 + 3 * h, (all[3] as TripLog.Entry.OfTrip).trip.start)
        // Its consumption and battery from the trip it covers
        assertEquals(15f, (all[2] as TripLog.Entry.OfReading).per100!!, 0.1f)
        assertEquals(90, (all[2] as TripLog.Entry.OfReading).startSoc)
    }

    @Test fun filter() {
        val trips = listOf(trip(t0 + h, 80, 20f))
        val done = TripLog.Reading(t0, 80, t0 + 2 * h, 100)
        assertEquals(2, TripLog.entries(trips, null, null, listOf(done), t0 + 3 * h, TripLog.Filter.ALL).size)
        assertTrue(TripLog.entries(trips, null, null, listOf(done), t0 + 3 * h, TripLog.Filter.TRIPS).all { it is TripLog.Entry.OfTrip })
        assertTrue(TripLog.entries(trips, null, null, listOf(done), t0 + 3 * h, TripLog.Filter.READINGS).all { it is TripLog.Entry.OfReading })
    }

    @Test fun clearKeepsTheReadingInProgress() {
        TripLog.add(sp, trip(t0, 80, 20f))
        TripLog.startReading(sp, t0, 80); TripLog.stopReading(sp, t0 + h, 100)
        TripLog.startReading(sp, t0 + 2 * h, 100)
        TripLog.clearAll(sp)
        assertTrue(TripLog.load(sp).isEmpty())
        assertTrue(TripLog.readings(sp).isEmpty())
        assertEquals("the reading in progress goes on", true, TripLog.reading(sp)?.running)
    }
}
