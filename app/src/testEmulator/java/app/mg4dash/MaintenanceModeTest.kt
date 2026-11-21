package app.mg4dash

import app.mg4dash.pages.ClimateActivity
import app.mg4dash.system.Prefs
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
 * In maintenance mode nothing of MG4Dash reacts (21/11, #123): any of its screens opened meanwhile closes at once
 * and silently, with no offer and nothing else opened. The way back is the three-finger gesture, or MG4Dash chosen
 * again as the home in Android's settings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MaintenanceModeTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    private fun opened(a: android.app.Activity) = shadowOf(a).nextStartedActivity?.component?.className

    @Test fun pagesCloseSilentlyInMaintenance() {
        Prefs(ctx).valetMode = true
        val page = Robolectric.buildActivity(ClimateActivity::class.java).setup().get()
        assertTrue("closed at once", page.isFinishing)
        assertNull("nothing opened in its place", opened(page))
        assertNull("no window", org.robolectric.shadows.ShadowDialog.getLatestDialog())
    }

    @Test fun settingsCloseSilentlyInMaintenance() {
        Prefs(ctx).valetMode = true
        val s = Robolectric.buildActivity(app.mg4dash.settings.SettingsActivity::class.java).setup().get()
        assertTrue(s.isFinishing)
        assertNull(opened(s))
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

    @Test fun maintenanceNotLeftUnlessChosenAsHome() {
        // The emulator build: never taken for the driver's choice (no HOME resolution to trust)
        assertFalse(app.mg4dash.system.Maintenance.chosenAsHome(ctx))
    }

    @Test fun anAppListBringsMG4DashBackButNotAShortcut() {
        val m = app.mg4dash.system.Maintenance
        // A launcher's app drawer: the way back
        assertTrue(m.openedFromAppList(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)))
        // HOME (the car's Home key) or a page asked by another app: never
        assertFalse(m.openedFromAppList(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)))
        assertFalse(m.openedFromAppList(android.content.Intent().setClassName("app.mg4dash", "app.mg4dash.pages.ClimateActivity")))
        assertFalse(m.openedFromAppList(null))
    }

    @Test fun fogButtonOverlayOffInMaintenance() {
        val p = Prefs(ctx)
        p.shortcutsMenu = true
        p.valetMode = true
        assertFalse(app.mg4dash.system.FogOverlay.hides(ctx))
    }
}