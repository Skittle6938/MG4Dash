package app.mg4dash

import android.content.Context
import app.mg4dash.energy.BatteryCapacity
import app.mg4dash.system.Prefs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Usable capacity measured while driving and battery health (SOH): the measurement across trips and restarts, the
 * 30-day smoothing, the stored history. The formulas alone are in EnergyEstimatesTest and UnitsAndCapacityTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BatteryCapacityTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = Prefs(ctx)
    private val day = 24 * 3600_000L

    /** The measurement in progress lives in the object: back to what the (empty) preferences say. */
    @Before @After fun freshMeasurement() {
        val c = BatteryCapacity::class.java
        for (f in listOf("accKwh", "accDrop", "accKm")) c.getDeclaredField(f).apply { isAccessible = true }.setFloat(null, 0f)
        c.getDeclaredField("loaded").apply { isAccessible = true }.setBoolean(null, false)
    }

    private fun store(vararg s: BatteryCapacity.Sample) {
        val arr = JSONArray()
        s.forEach { arr.put(JSONObject().put("t", it.at).put("k", it.kwh.toDouble()).put("d", it.drop).put("m", it.km.toDouble())) }
        prefs.raw.edit().putString("cap_samples", arr.toString()).commit()
    }

    // ── Measurement while driving ──

    @Test fun nothingMeasuredYet() {
        assertNull(BatteryCapacity.estimate(ctx))
        assertNull(BatteryCapacity.health(ctx))
        val p = BatteryCapacity.progress(ctx)
        assertEquals(0f, p.drop, 0f)
        assertNull(p.provisional)
    }

    @Test fun provisionalCapacityFromFivePercent() {
        BatteryCapacity.accumulate(ctx, 1.2f, 2f, 10f)
        assertNull("under 5 %", BatteryCapacity.progress(ctx).provisional)
        BatteryCapacity.accumulate(ctx, 1.8f, 3f, 15f)
        val p = BatteryCapacity.progress(ctx)
        assertEquals(5f, p.drop, 0.001f)
        assertEquals(25f, p.km, 0.001f)
        assertEquals(60f, p.provisional!!, 0.01f)
        // Not yet a measurement
        assertTrue(BatteryCapacity.samples(ctx).isEmpty())
    }

    @Test fun regenerationAndChargingAreDeducted() {
        // Out 10 kWh, back 1 kWh downhill; 16 % lost then 1 % back: 9 kWh for 15 %
        BatteryCapacity.accumulate(ctx, 10f, 14f, 50f)
        BatteryCapacity.accumulate(ctx, -1f, -1f, 2f)
        BatteryCapacity.accumulate(ctx, 0f, 2f, 3f)
        assertEquals(60f, BatteryCapacity.samples(ctx).single().kwh, 0.01f)
    }

    @Test fun measurementInProgressSurvivesARestart() {
        BatteryCapacity.accumulate(ctx, 3f, 6f, 30f)
        BatteryCapacity.flush(ctx)
        freshMeasurement()
        val p = BatteryCapacity.progress(ctx)
        assertEquals(6f, p.drop, 0.001f)
        assertEquals(30f, p.km, 0.001f)
        assertEquals(50f, p.provisional!!, 0.01f)
    }

    // ── Smoothing ──

    @Test fun onlyTheLastThirtyDaysWhenThereAreSome() {
        val now = System.currentTimeMillis()
        store(BatteryCapacity.Sample(now - 90 * day, 70f, 50), BatteryCapacity.Sample(now - day, 58f, 20))
        assertEquals(58f to 1, BatteryCapacity.estimate(ctx)!!)
    }

    @Test fun olderMeasurementsWhenNoneIsRecent() {
        val now = System.currentTimeMillis()
        store(BatteryCapacity.Sample(now - 90 * day, 60f, 20), BatteryCapacity.Sample(now - 60 * day, 50f, 20))
        assertEquals(55f, BatteryCapacity.estimate(ctx)!!.first, 0.01f)
    }

    @Test fun damagedHistoryIsIgnored() {
        prefs.raw.edit().putString("cap_samples", "{not json").commit()
        assertTrue(BatteryCapacity.samples(ctx).isEmpty())
        assertNull(BatteryCapacity.estimate(ctx))
    }

    @Test fun samplesBeforeKmWereRecordedStillRead() {
        prefs.raw.edit().putString("cap_samples", """[{"t":1,"k":60.0,"d":15}]""").commit()
        assertEquals(0f, BatteryCapacity.samples(ctx).single().km, 0f)
    }

    // ── Health ──

    @Test fun unknownModelTakesTheSmallestReference() {
        prefs.batteryModel = 99
        assertEquals(49f, BatteryCapacity.nominal(ctx), 0f)
    }

    @Test fun referenceEnteredByHandWins() {
        prefs.batteryModel = 64
        prefs.batteryNewKwh = 62.5f
        assertEquals(62.5f, BatteryCapacity.nominal(ctx), 0f)
        assertEquals(61f, BatteryCapacity.modelNominal(ctx), 0f)
    }

    @Test fun healthAgainstTheModel() {
        prefs.batteryModel = 64
        val now = System.currentTimeMillis()
        store(BatteryCapacity.Sample(now - day, 57.95f, 20, 120f), BatteryCapacity.Sample(now - 2 * day, 57.95f, 20, 80f))
        val h = BatteryCapacity.health(ctx)
        assertNotNull(h); h!!
        assertEquals(95f, h.percent, 0.05f)
        assertEquals(200f, h.km, 0.01f)
        assertEquals(2, h.count)
    }

    @Test fun healthNeverOverAHundred() {
        prefs.batteryModel = 51
        store(BatteryCapacity.Sample(System.currentTimeMillis(), 55f, 20))
        assertEquals(100f, BatteryCapacity.health(ctx)!!.percent, 0f)
    }

    @Test fun measuredCapacityIsUsed() {
        // The emulator build has no car: MG4Dash's measurement, to the tenth
        store(BatteryCapacity.Sample(System.currentTimeMillis(), 57.34f, 20))
        BatteryCapacity.apply(ctx)
        assertEquals(57.3f, prefs.batteryKwh, 0.001f)
    }

    @Test fun nothingMeasuredKeepsTheModelsCapacity() {
        prefs.batteryModel = 64
        BatteryCapacity.apply(ctx)
        assertEquals(61f, prefs.batteryKwh, 0f)
    }
}
