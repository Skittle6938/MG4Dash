package app.mg4dash

import app.mg4dash.ui.EmbeddedApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** CarPlay opened without the status bar (#1): always in the immersive policy, with or without an embedded app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CarPlayBarsTest {
    private val own = "app.mg4dash"

    @Test fun carPlayAlone() = assertEquals("immersive.full=com.allgo.carplay.service", EmbeddedApp.policy(own, null, carPlay = true))

    @Test fun withAnEmbeddedApp() =
        assertEquals("immersive.full=app.mg4dash,com.waze,com.allgo.carplay.service", EmbeddedApp.policy(own, "com.waze", carPlay = true))

    @Test fun noCarPlayAsBefore() {
        assertEquals("immersive.full=app.mg4dash,com.waze", EmbeddedApp.policy(own, "com.waze", carPlay = false))
        assertNull(EmbeddedApp.policy(own, null, carPlay = false))
    }

    @Test fun onlyAValueSetByMG4Dash() {
        assertTrue(EmbeddedApp.ours(null, own))
        assertTrue(EmbeddedApp.ours("", own))
        assertTrue(EmbeddedApp.ours("immersive.full=app.mg4dash,com.waze", own))
        assertTrue(EmbeddedApp.ours("immersive.full=com.allgo.carplay.service", own))
        assertFalse("another app's setting is left alone", EmbeddedApp.ours("immersive.full=*", own))
    }
}
