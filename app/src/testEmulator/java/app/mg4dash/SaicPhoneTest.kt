package app.mg4dash

import android.telephony.TelephonyManager.CALL_STATE_IDLE
import android.telephony.TelephonyManager.CALL_STATE_OFFHOOK
import android.telephony.TelephonyManager.CALL_STATE_RINGING
import app.mg4dash.system.SaicPhone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The SAIC phone's call shrunk to its floating card (#93): on Home, and once a call is placed or answered. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SaicPhoneTest {
    @Test fun shrunkOncePlacedOrAnswered() {
        assertTrue("placed", SaicPhone.shrinkWhen(CALL_STATE_IDLE, CALL_STATE_OFFHOOK))
        assertTrue("answered", SaicPhone.shrinkWhen(CALL_STATE_RINGING, CALL_STATE_OFFHOOK))
        assertFalse("while it rings: the large screen, to answer", SaicPhone.shrinkWhen(CALL_STATE_IDLE, CALL_STATE_RINGING))
        assertFalse("once only", SaicPhone.shrinkWhen(CALL_STATE_OFFHOOK, CALL_STATE_OFFHOOK))
        assertFalse("hung up", SaicPhone.shrinkWhen(CALL_STATE_OFFHOOK, CALL_STATE_IDLE))
    }

    @Test fun phoneStateBroadcastRead() {
        assertEquals(CALL_STATE_OFFHOOK, SaicPhone.stateOf(android.telephony.TelephonyManager.EXTRA_STATE_OFFHOOK))
        assertEquals(CALL_STATE_RINGING, SaicPhone.stateOf(android.telephony.TelephonyManager.EXTRA_STATE_RINGING))
        assertEquals(CALL_STATE_IDLE, SaicPhone.stateOf(android.telephony.TelephonyManager.EXTRA_STATE_IDLE))
        assertEquals(null, SaicPhone.stateOf(null))
    }

    @Test fun homeSendsTheStockLauncherCommandOnlyWithTheA9Phone() {
        val app = RuntimeEnvironment.getApplication()
        while (shadowOf(app).nextStartedService != null) { /* the app's own services, started at its launch */ }
        // No SAIC phone app (SWI68 / SWI165, or the emulator): nothing sent
        SaicPhone.onHome(app)
        assertEquals(null, shadowOf(app).nextStartedService)
        // With it: the stock launcher's command to its PhoneService
        val f = SaicPhone::class.java.getDeclaredField("present").apply { isAccessible = true }
        f.set(SaicPhone, true)
        try {
            SaicPhone.onHome(app)
            val i = shadowOf(app).nextStartedService
            assertEquals("com.saicmotor.btphone/.service.PhoneService", i.component!!.flattenToShortString())
            assertTrue(i.getParcelableExtra<com.saicmotor.common.CommandParcelable>("command").toString().contains("mEvent=16416"))
        } finally { f.set(SaicPhone, null) }
    }

    /** A call placed or answered: the launcher's command (every A9 version) and the phone's own (not on SWI131 / SWI86). */
    @Test fun callShrunkWithBothCommands() {
        val app = RuntimeEnvironment.getApplication()
        while (shadowOf(app).nextStartedService != null) { }
        SaicPhone.shrinkCall(app)
        val events = generateSequence { shadowOf(app).nextStartedService }
            .map { it.getParcelableExtra<com.saicmotor.common.CommandParcelable>("command").toString() }.toList()
        assertEquals(2, events.size)
        assertTrue(events[0].contains("mEvent=16416") && events[0].contains("mTargetType=1"))
        assertTrue(events[1].contains("mEvent=16423"))
    }
}
