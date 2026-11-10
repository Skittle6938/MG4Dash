package app.mg4dash

import app.mg4dash.energy.TripLog
import app.mg4dash.ui.TripText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The cost of the trips with a kWh price (#102): computed, rounded, none without a price, the price kept with each trip. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "fr-rFR")
class TripCostTest {
    private fun trip(kwh: Float, price: Float?) = TripLog.Trip(0, 1_800_000, 100, 120, 20f, kwh, 90, 85, price)

    @Test fun computed() {
        assertEquals(1.0f, trip(4f, 0.25f).cost!!, 0.001f)
        assertEquals("the costs added up", 1.5f, TripLog.costOf(listOf(trip(4f, 0.25f), trip(2f, 0.25f)))!!, 0.001f)
    }

    @Test fun roundedInTheSystemsCurrency() {
        assertEquals("1,23 €", TripText.money(1.234f).replace(' ', ' ').replace(' ', ' '))
        assertEquals("—", TripText.money(null))
    }

    @Test fun noPriceNoCost() {
        assertNull(trip(4f, null).cost)
        assertNull("no kWh measured: unknown, not free", trip(0f, 0.25f).cost)
        assertNull(TripLog.costOf(listOf(trip(4f, null))))
        app.mg4dash.system.Prefs(RuntimeEnvironment.getApplication()).kwhPrice = 0f
        assertNull("no Cost column without a price", TripText.price(RuntimeEnvironment.getApplication()))
    }

    @Test fun priceKeptWithEachTrip() {
        val sp = RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE)
        sp.edit().clear().commit()
        TripLog.add(sp, trip(4f, 0.25f))
        // The price changes afterwards: the trip recorded keeps its own
        app.mg4dash.system.Prefs(RuntimeEnvironment.getApplication()).kwhPrice = 0.40f
        val back = TripLog.load(sp).single()
        assertEquals(0.25f, back.price!!, 0.0001f)
        assertEquals(1.0f, back.cost!!, 0.001f)
        // Trips recorded before the price existed read back without one
        assertNull(TripLog.Trip.decode("0,1800000,100,120,20.0,4.0,90,85")!!.price)
    }

    @Test fun readingAddsItsTrips() {
        val r = TripLog.Reading(0, 100, 4_000_000, 140)
        val trips = listOf(trip(4f, 0.25f), TripLog.Trip(1_900_000, 3_600_000, 120, 140, 20f, 2f, 85, 80, 0.30f))
        assertEquals(1.6f, TripLog.covered(r, r.end, trips).cost!!, 0.001f)
    }
}
