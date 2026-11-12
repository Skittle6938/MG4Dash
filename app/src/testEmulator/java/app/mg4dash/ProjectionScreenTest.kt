package app.mg4dash

import app.mg4dash.media.MediaHub
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The phone projection's screen of the apps MG4Dash knows, MG4Link's renamed one included. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProjectionScreenTest {
    @Test fun projectionScreens() {
        assertTrue("DiAuto, Headunit Revived, MG4Link until now", MediaHub.isProjectionScreen("com.andrerinas.openheadunit.AapProjectionActivity"))
        assertTrue("MG4Link's next version", MediaHub.isProjectionScreen("com.allgo.app.androidauto.ProjectionActivity"))
        assertFalse("its settings", MediaHub.isProjectionScreen("com.andrerinas.openheadunit.main.DaSettingsActivity"))
        assertFalse("another app's ProjectionActivity", MediaHub.isProjectionScreen("com.saicmotor.hmi.clusterprojection.ProjectionActivity"))
    }
}
