package app.mg4dash

import app.mg4dash.energy.BatteryCapacity
import app.mg4dash.system.Prefs
import app.mg4dash.system.Units
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/** Display units (#9, #11) and the battery capacity measured while driving (#77). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UnitsAndCapacityTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var locale: Locale

    @Before fun setUp() { locale = Locale.getDefault(); Locale.setDefault(Locale.US) }
    @After fun tearDown() { Locale.setDefault(locale) }

    @Test fun kilometres() {
        Prefs(context).units = 1
        assertFalse(Units.miles(context))
        assertEquals("284 km", Units.distText(context, 284f))
        assertEquals("50.9 km", Units.distText(context, 50.94f, 1))
        assertEquals("90 km/h", Units.speedText(context, 90f))
        assertEquals(16.5f, Units.cons(context, 16.5f), 0.001f)
        assertEquals("kWh/100 km", Units.consUnit(context))
    }

    @Test fun miles() {
        Prefs(context).units = 2
        assertTrue(Units.miles(context))
        assertEquals("100 mi", Units.distText(context, 160.9344f))
        assertEquals("56 mph", Units.speedText(context, 90f))
        // mi/kWh = 62.14 / kWh/100 km
        assertEquals(3.77f, Units.cons(context, 16.5f), 0.01f)
        assertEquals("mi/kWh", Units.consUnit(context))
        // The stacked chart stays in kWh per 100 miles
        assertEquals(16.09f, Units.stackValue(context, 10f), 0.01f)
    }

    @Test fun tyrePressure() {
        val p = Prefs(context)
        p.units = 1
        p.pressureUnit = 0
        assertEquals("2.50 bar", Units.pressureText(context, 250))   // auto with km: bar
        p.units = 2
        assertEquals("36.3 psi", Units.pressureText(context, 250))   // auto with miles: psi
        p.pressureUnit = 1
        assertEquals("2.50 bar", Units.pressureText(context, 250))
        p.pressureUnit = 3
        assertEquals("250 kPa", Units.pressureText(context, 250))
    }

    @Test fun capacityAndHealthMeasuredWhileDriving() {
        val p = Prefs(context)
        p.batteryModel = 64
        assertEquals(61f, BatteryCapacity.modelNominal(context), 0.001f)
        assertNull(BatteryCapacity.health(context))

        // 15 % over several seconds with 9 kWh out of the battery: 60 kWh measured
        repeat(15) { BatteryCapacity.accumulate(context, 0.6f, 1f, 2f) }
        val samples = BatteryCapacity.samples(context)
        assertEquals(1, samples.size)
        assertEquals(60f, samples[0].kwh, 0.1f)
        assertEquals(15, samples[0].drop)
        assertEquals(30f, samples[0].km, 0.1f)

        // Health against the model's 61 kWh, or the new capacity entered by hand
        assertEquals(98.4f, BatteryCapacity.health(context)!!.percent, 0.1f)
        p.batteryNewKwh = 60f
        assertEquals(100f, BatteryCapacity.health(context)!!.percent, 0.01f)

        // The measurement starts again from zero
        val progress = BatteryCapacity.progress(context)
        assertEquals(0f, progress.drop, 0.001f)
        assertNotNull(BatteryCapacity.estimate(context))
    }

    @Test fun implausibleMeasurementIsNotKept() {
        // 15 % for 1.5 kWh: a capacity of 10 kWh is rejected
        repeat(15) { BatteryCapacity.accumulate(context, 0.1f, 1f, 1f) }
        assertTrue(BatteryCapacity.samples(context).isEmpty())
    }
}
