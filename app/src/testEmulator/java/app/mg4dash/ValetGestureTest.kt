package app.mg4dash

import android.content.Context
import android.view.MotionEvent
import app.mg4dash.system.Prefs
import app.mg4dash.ui.ShortcutsPanel
import app.mg4dash.ui.ValetGesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Maintenance mode (Discussion #111): three fingers from the top bring the way back to MG4Dash, one finger stays the car's. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ValetGestureTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = Prefs(ctx)

    @Before fun clear() { prefs.raw.edit().clear().commit() }

    @Test fun onlyThreeFingersSlidingDown() {
        assertTrue(ValetGesture.isThreeFingerPull(40f, 3, 1f))
        assertTrue(ValetGesture.isThreeFingerPull(90f, 4, 2f))
        assertFalse("too short", ValetGesture.isThreeFingerPull(39f, 3, 1f))
        assertFalse("two fingers", ValetGesture.isThreeFingerPull(200f, 2, 1f))
        assertFalse("one finger", ValetGesture.isThreeFingerPull(200f, 1, 1f))
        assertFalse("upwards", ValetGesture.isThreeFingerPull(-80f, 3, 1f))
    }

    @Test fun stripKeptInMaintenanceModeEvenWithoutTheMenu() {
        prefs.shortcutsMenu = false
        assertFalse(ShortcutsPanel.GlobalStrip.wanted(ctx))
        prefs.valetMode = true
        assertTrue(ShortcutsPanel.GlobalStrip.wanted(ctx))
    }

    @Test fun touchHandedBackAsTheSameFinger() {
        val e = ValetGesture.event(10L, 12L, MotionEvent.ACTION_DOWN, 300f, 4f, 7, 0)
        assertEquals(MotionEvent.ACTION_DOWN, e.action)
        assertEquals(300f, e.rawX, 0f); assertEquals(4f, e.rawY, 0f)
        assertEquals(7, e.deviceId)
        assertEquals(android.view.InputDevice.SOURCE_TOUCHSCREEN, e.source)
        assertEquals(10L, e.downTime)
    }
}
