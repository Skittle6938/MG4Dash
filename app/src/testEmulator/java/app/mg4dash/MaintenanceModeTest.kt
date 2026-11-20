package app.mg4dash

import app.mg4dash.pages.ClimateActivity
import app.mg4dash.system.MaintenanceActivity
import app.mg4dash.system.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * In maintenance mode, settings and pages offer a way back to MG4Dash. The projection shortcut closes silently.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MaintenanceModeTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    private fun opened(a: android.app.Activity) = shadowOf(a).nextStartedActivity?.component?.className

    @Test fun pagesCloseInMaintenance() {
        Prefs(ctx).valetMode = true
        val page = Robolectric.buildActivity(ClimateActivity::class.java).setup().get()
        assertTrue("closed at once", page.isFinishing)
        assertEquals(MaintenanceActivity::class.java.name, opened(page))
    }

    @Test fun settingsCloseInMaintenance() {
        Prefs(ctx).valetMode = true
        val s = Robolectric.buildActivity(app.mg4dash.settings.SettingsActivity::class.java).setup().get()
        assertTrue(s.isFinishing)
        assertEquals(MaintenanceActivity::class.java.name, opened(s))
    }

    @Test fun projectionShortcutDoesNothingInMaintenance() {
        Prefs(ctx).valetMode = true
        val a = Robolectric.buildActivity(app.mg4dash.ui.OpenProjectionActivity::class.java).setup().get()
        assertTrue(a.isFinishing)
        assertNull(shadowOf(a).nextStartedActivity)
    }

    @Test fun pagesOpenNormallyOtherwise() {
        Prefs(ctx).valetMode = false
        val page = Robolectric.buildActivity(ClimateActivity::class.java).setup().get()
        assertFalse(page.isFinishing)
        assertNull(opened(page))
    }

    @Test fun theOfferIsOnlyForMaintenance() {
        Prefs(ctx).valetMode = false
        assertTrue(Robolectric.buildActivity(MaintenanceActivity::class.java).setup().get().isFinishing)
        Prefs(ctx).valetMode = true
        val m = Robolectric.buildActivity(MaintenanceActivity::class.java).setup().get()
        assertFalse(m.isFinishing)
        assertTrue("its offer shown", org.robolectric.shadows.ShadowDialog.getLatestDialog()?.isShowing == true)
    }

    @Test fun fogButtonOverlayOffInMaintenance() {
        val p = Prefs(ctx)
        p.shortcutsMenu = true
        p.valetMode = true
        assertFalse(app.mg4dash.system.FogOverlay.hides(ctx))
    }
}
