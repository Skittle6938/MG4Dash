package app.mg4dash

import android.content.Context
import android.os.Looper
import app.mg4dash.energy.TripLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Calendar

/** The last trips (#102): recorded at the next start, 50 kept, today's totals from midnight, kept after a restart. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1920dp-h720dp-land-mdpi")
class TripLogTest {
    private val sp get() = RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", Context.MODE_PRIVATE)

    @Before fun empty() { sp.edit().clear().commit(); TripLog.load(sp) }

    private fun at(day: Int, h: Int, m: Int): Long = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, day, h, m, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun trip(start: Long, minutes: Int, km0: Int, km: Float, kwh: Float = km * 0.15f) =
        TripLog.Trip(start, start + minutes * 60_000L, km0, km0 + Math.round(km), km, kwh, 90, 85)

    @Test fun recordedAtTheNextStart() {
        val t = trip(at(9, 13, 12), 46, 18302, 29.4f, 4.03f)
        TripLog.saveCurrent(sp, t)
        assertTrue("not in the list while it runs", TripLog.load(sp).isEmpty())
        TripLog.closeCurrent(sp)
        val all = TripLog.load(sp)
        assertEquals(listOf(t), all)
        assertEquals(46L, all[0].minutes)
        assertEquals(13.7f, all[0].per100!!, 0.05f)
        assertNull("closed once", TripLog.current.value)
    }

    @Test fun tooShortNotKept() {
        TripLog.add(sp, trip(at(9, 8, 0), 3, 18000, 0.05f))
        assertTrue(TripLog.load(sp).isEmpty())
    }

    @Test fun fiftyKeptLatestFirst() {
        repeat(60) { i -> TripLog.add(sp, trip(at(1, 8, 0) + i * 3_600_000L, 20, 17000 + i * 10, 10f)) }
        val all = TripLog.load(sp)
        assertEquals(TripLog.MAX, all.size)
        assertEquals("the latest first", 17000 + 59 * 10, all.first().startKm)
        assertEquals("the oldest ten dropped", 17000 + 10 * 10, all.last().startKm)
    }

    @Test fun todayFromMidnight() {
        val trips = listOf(trip(at(9, 0, 10), 25, 18250, 7f), trip(at(8, 23, 30), 25, 18240, 10f), trip(at(8, 18, 30), 34, 18219, 20.8f))
        val current = trip(at(9, 8, 2), 55, 18257, 22.1f)
        val day = TripLog.today(trips, current, at(9, 9, 0))
        assertEquals(2, day.trips)
        assertEquals(29.1f, day.km, 0.01f)
        assertEquals(80L, day.minutes)
        assertEquals("odometer at the first start of the day", 18250, day.firstKm)
        // Just after midnight: the day starts again, yesterday's trips no longer count
        val next = TripLog.today(trips + current, null, at(10, 0, 5))
        assertEquals(0, next.trips)
        assertNull(next.firstKm)
    }

    @Test fun keptAfterARestart() {
        TripLog.add(sp, trip(at(9, 10, 40), 41, 18279, 22.6f))
        TripLog.saveCurrent(sp, trip(at(9, 13, 12), 30, 18302, 18f))
        // MG4Dash started again: the list read back, the trip in progress recorded at its first pass
        val again = TripLog.load(sp)
        assertEquals(1, again.size)
        TripLog.closeCurrent(sp)
        assertEquals(listOf(18302, 18279), TripLog.load(sp).map { it.startKm })
    }

    @Test fun manualReadingGoesOnWhenTheCarTurnsOff() {
        TripLog.startReading(sp, at(9, 8, 2), 18261)
        // MG4Dash stopped with the car and started again: the reading is still there, running
        val r = TripLog.reading(sp)
        assertNotNull(r); assertTrue(r!!.running)
        assertEquals(83, r.km(18344))
        TripLog.stopReading(sp, at(9, 17, 40), 18344)
        val done = TripLog.reading(sp)!!
        assertEquals(false, done.running)
        assertEquals("kept after it stops", 83, done.km(18400))
        TripLog.startReading(sp, at(10, 8, 0), 18400)
        assertEquals(0, TripLog.reading(sp)!!.km(18400))
    }

    @Test fun pageOpensFromTheCardAndTheVehicleLine() {
        val card = app.mg4dash.ui.TripsCard(Robolectric.buildActivity(android.app.Activity::class.java).setup().get())
        card.show()
        card.performClick()
        val opened = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(app.mg4dash.pages.TripsActivity::class.java.name, opened.component?.className)

        val vehicle = Robolectric.buildActivity(app.mg4dash.pages.VehicleActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        fun find(v: android.view.View): android.view.View? {
            if (v is android.widget.TextView && v.text.toString() == vehicle.getString(R.string.tr_today)) return v.parent?.parent as? android.view.View
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        val line = find(vehicle.window.decorView)
        assertNotNull("today's line on the Vehicle page", line)
        line!!.performClick()
        assertEquals(app.mg4dash.pages.TripsActivity::class.java.name, shadowOf(vehicle).nextStartedActivity.component?.className)
    }
}
