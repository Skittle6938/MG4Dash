package app.mg4dash

import app.mg4dash.system.LauncherLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Camera left to home by the SWI173 launcher (#78): same orders as its LauncherPresenter. */
@RunWith(RobolectricTestRunner::class)
class LauncherLinkTest {
    @Test fun avm() {
        assertEquals("com.saicmotor.avm.STOP", LauncherLink.cameraIntent("com.saicmotor.avm", 0)?.action)
        val open = LauncherLink.cameraIntent("com.saicmotor.avm", 1)
        assertEquals("com.saicmotor.avm.START_ENTER", open?.action)
        assertEquals("com.saicmotor.avm.service.SaicMotorAvmService", open?.component?.className)
    }

    @Test fun aroundView() {
        assertEquals("com.saicmotor.hmi.aroundview.AvmService", LauncherLink.cameraIntent("com.saicmotor.hmi.aroundview", 1)?.component?.className)
    }

    @Test fun rearCamera() {
        assertEquals(0, LauncherLink.cameraIntent("com.saicmotor.evs", 0)?.getIntExtra("state", -1))
        assertEquals(1, LauncherLink.cameraIntent("com.saicmotor.evs", 1)?.getIntExtra("state", -1))
    }

    @Test fun otherAppsIgnored() {
        assertNull(LauncherLink.cameraIntent("com.saicmotor.radio", 8))
        assertNull(LauncherLink.cameraIntent(null, 0))
    }
}
