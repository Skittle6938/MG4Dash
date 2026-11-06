package app.mg4dash

import app.mg4dash.shortcuts.HaLive
import app.mg4dash.shortcuts.HaLive.Kind
import app.mg4dash.shortcuts.Shortcuts
import app.mg4dash.system.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class HaLiveTest {
    @Test fun cover() {
        assertEquals(Kind.ACTIVE, HaLive.parse("cover.garage", "open", null).kind)
        assertEquals(Kind.MOVING, HaLive.parse("cover.garage", "opening", null).kind)
        assertEquals(Kind.MOVING, HaLive.parse("cover.garage", "closing", null).kind)
        assertEquals(Kind.INACTIVE, HaLive.parse("cover.garage", "closed", null).kind)
        assertEquals(R.string.ha_closed, HaLive.parse("cover.garage", "closed", null).text)
    }

    @Test fun doorSensorReadsOpenClosed() {
        assertEquals(R.string.ha_open, HaLive.parse("binary_sensor.garage", "on", "garage_door").text)
        assertEquals(R.string.ha_closed, HaLive.parse("binary_sensor.garage", "off", "garage_door").text)
        assertEquals(R.string.ha_active, HaLive.parse("binary_sensor.motion", "on", "motion").text)
    }

    @Test fun lockSwitchAndUnavailable() {
        assertEquals(Kind.ACTIVE, HaLive.parse("lock.door", "unlocked", null).kind)
        assertEquals(Kind.INACTIVE, HaLive.parse("lock.door", "locked", null).kind)
        assertEquals(R.string.ha_on, HaLive.parse("light.x", "on", null).text)
        assertEquals(Kind.UNAVAILABLE, HaLive.parse("cover.garage", "unavailable", null).kind)
        assertEquals(Kind.UNAVAILABLE, HaLive.parse("switch.x", "unknown", null).kind)
    }

    @Test fun entityShown() {
        val prefs = Prefs(RuntimeEnvironment.getApplication())
        val script = Shortcuts.Slot.Ha("script.garage", "Garage")
        val cover = Shortcuts.Slot.Ha("cover.garage", "Garage")
        // A script has no state of its own; a cover shows its own
        assertNull(HaLive.entityFor(prefs, 50, script))
        assertEquals("cover.garage", HaLive.entityFor(prefs, 51, cover))
        // Chosen sensor, then no state
        HaLive.setChoice(prefs, 50, "binary_sensor.garage_door")
        assertEquals("binary_sensor.garage_door", HaLive.entityFor(prefs, 50, script))
        HaLive.setNone(prefs, 51)
        assertNull(HaLive.entityFor(prefs, 51, cover))
        // A new action goes back to automatic
        Shortcuts.set(prefs, 50, Shortcuts.Slot.Ha("script.other", "Other"))
        assertNull(HaLive.choice(prefs, 50))
    }
}
