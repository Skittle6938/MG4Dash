package app.mg4dash

import android.os.Looper
import app.mg4dash.radar.RadarRepo
import app.mg4dash.system.Prefs
import app.mg4dash.ui.Stepper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The stepper (24/11): − and + within the limits, a typed value, durations and hours written in their unit, old values kept. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "fr")
class StepperTest {
    @Test fun stepsAndClamps() {
        val a = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        var v = 28
        val s = Stepper(a, 0, 30, label = { "+$it km/h" }, typedTitle = "Forte", get = { v }) { v = it }
        s.nudge(1); s.nudge(1); s.nudge(1)
        assertEquals(30, v)
        v = 1; s.nudge(-1); s.nudge(-1)
        assertEquals(0, v)
        assertEquals(30, s.clamp(99)); assertEquals(0, s.clamp(-4))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun durations() {
        assertEquals("5 s", Stepper.duration(5)); assertEquals("2 min", Stepper.duration(120)); assertEquals("1 min 30", Stepper.duration(90))
        assertEquals("1 h", Stepper.duration(3600))
        assertEquals(5, Stepper.durationStep(10, 1)); assertEquals(30, Stepper.durationStep(60, 1)); assertEquals(5, Stepper.durationStep(60, -1))
        assertEquals("6 h", Stepper.hours(6, "j")); assertEquals("7 j", Stepper.hours(168, "j"))
    }

    @Test fun typedDurations() {
        assertEquals(90, Stepper.parseDuration("90")); assertEquals(90, Stepper.parseDuration("1:30")); assertEquals(120, Stepper.parseDuration("2 min"))
        assertEquals(5400, Stepper.parseDuration("1h30")); assertEquals(10, Stepper.parseDuration("10 s")); assertNull(Stepper.parseDuration("abc"))
    }

    /** Values stored from the old lists are read as they are; a radius beyond the new range comes back within it. */
    @Test fun oldValuesKept() {
        val p = Prefs(RuntimeEnvironment.getApplication())
        p.raw.edit().clear().putInt("radar_refresh_hours", 168).putInt("radar_prepare_km", 80).putInt("radar_rep_light", 300).commit()
        assertEquals(168, p.radarRefreshHours)
        assertEquals(50, p.radarPrepareKm)
        assertEquals(20, RadarRepo.prepareRadius(20)); assertEquals(5, RadarRepo.prepareRadius(1)); assertEquals(25, RadarRepo.prepareRadius(27))
        assertEquals(300, p.radarRepeat(app.mg4dash.radar.RadarAlert.Kind.LIGHT))
    }
}
