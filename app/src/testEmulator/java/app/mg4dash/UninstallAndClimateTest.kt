package app.mg4dash

import android.content.pm.ApplicationInfo
import app.mg4dash.car.CarBridge
import app.mg4dash.car.VehicleState
import app.mg4dash.system.Uninstaller
import app.mg4dash.system.Uninstaller.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Uninstalling from the settings (#111), and Keep climate on's set point and fan (#26), 19/11. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UninstallAndClimateTest {

    private class Fake(val home: Boolean = true, val installed: Set<String>, val failing: Set<String> = emptySet()) : Uninstaller.Ops {
        val log = ArrayList<String>()
        override fun stockHomeBack(): Boolean { log += "home"; return home }
        override fun installed(pkg: String) = pkg in installed
        override fun remove(pkg: String): Boolean { log += "remove $pkg"; return pkg !in failing }
        override fun clearSettings() { log += "settings" }
    }
    private val all = setOf("app.mg4dash.overlay.nofog", "app.mg4dash.web", "app.mg4dash.rescue", "app.mg4dash")

    @Test fun dashRemovesItselfLastAfterSaicHome() {
        val f = Fake(installed = all)
        val r = Uninstaller.uninstallDash(f, "app.mg4dash")
        assertNull(r.failed)
        assertEquals(listOf("home", "remove app.mg4dash.overlay.nofog", "remove app.mg4dash.web", "settings", "remove app.mg4dash.rescue", "remove app.mg4dash"), f.log)
        assertEquals(listOf(Step.HOME, Step.OVERLAY, Step.WEB, Step.SETTINGS, Step.RESCUE, Step.DASH), r.done)
    }

    @Test fun nothingRemovedWithoutSaicHome() {
        val f = Fake(home = false, installed = all)
        val r = Uninstaller.uninstallDash(f, "app.mg4dash")
        assertEquals(Step.HOME, r.failed)
        assertEquals(listOf("home"), f.log)
    }

    @Test fun rescueFailingKeepsDash() {
        // The rescue could not go: MG4Dash stays (otherwise the rescue would react to it going)
        val f = Fake(installed = all, failing = setOf("app.mg4dash.rescue"))
        val r = Uninstaller.uninstallDash(f, "app.mg4dash")
        assertEquals(Step.RESCUE, r.failed)
        assertFalse(f.log.contains("remove app.mg4dash"))
    }

    @Test fun anAddOnFailingStopsBeforeDash() {
        val f = Fake(installed = all, failing = setOf("app.mg4dash.overlay.nofog"))
        assertEquals(Step.OVERLAY, Uninstaller.uninstallDash(f, "app.mg4dash").failed)
        assertFalse(f.log.any { it == "remove app.mg4dash" || it == "remove app.mg4dash.rescue" })
    }

    @Test fun missingAddOnsSkipped() {
        val f = Fake(installed = setOf("app.mg4dash"))
        val r = Uninstaller.uninstallDash(f, "app.mg4dash")
        assertNull(r.failed)
        assertEquals(listOf("home", "settings", "remove app.mg4dash"), f.log)
    }

    @Test fun radioRemovalByFirmware() {
        // SWI69: an update of the system radio, removed; SWI68: a plain app beside hmi.radio, uninstalled
        assertEquals(Uninstaller.Removal.REVERT_UPDATE, Uninstaller.removalOf(ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))
        assertEquals(Uninstaller.Removal.UNINSTALL, Uninstaller.removalOf(0))
        assertEquals(Uninstaller.Removal.NOTHING, Uninstaller.removalOf(ApplicationInfo.FLAG_SYSTEM))
    }

    @Test fun keepClimateSetPointWithinTheCarsLimits() {
        // LO, the numbers, HI: each slot maps back to itself
        val slots = CarBridge.tempSlots
        for (i in 0 until slots) assertEquals(i, CarBridge.slotOf(CarBridge.tempForSlot(i, slots), slots))
        assertEquals(VehicleState.TEMP_MIN, CarBridge.tempForSlot(0, slots), 0f)
        assertEquals(VehicleState.TEMP_MAX, CarBridge.tempForSlot(slots - 1, slots), 0f)
    }

    @Test fun keepClimateChoiceFromTheMenu() {
        val p = app.mg4dash.system.Prefs(RuntimeEnvironment.getApplication())
        p.climateHoldTemp = 0f
        assertNull("the climate's own", app.mg4dash.climate.ClimateHold.chosenOf(p))
        p.climateHoldTemp = 21f; p.climateHoldFan = 30
        assertEquals(21f to VehicleState.FAN_MAX, app.mg4dash.climate.ClimateHold.chosenOf(p))
    }

    @Test fun keepClimateMenuShowsAndSavesIt() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit().putBoolean("tutorial_shortcuts_seen", true).commit()
        val act = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup().get()
        app.mg4dash.climate.ClimateHold.stop("test")
        app.mg4dash.climate.ClimateHoldPanel.show(act)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        fun all(v: android.view.View, out: MutableList<android.view.View> = ArrayList()): List<android.view.View> {
            out += v; if (v is android.view.ViewGroup) for (i in 0 until v.childCount) all(v.getChildAt(i), out); return out }
        val texts = all(act.window.decorView).filterIsInstance<android.widget.TextView>().map { it.text.toString() }
        assertTrue(texts.joinToString(), texts.any { it == ctx.getString(R.string.dm_temp) } && texts.any { it == ctx.getString(R.string.dm_fan) })
        // Prefilled from the climate, saved for the start
        assertTrue(app.mg4dash.system.Prefs(ctx).climateHoldTemp > 0f)
        assertTrue(app.mg4dash.system.Prefs(ctx).climateHoldFan in VehicleState.FAN_MIN..VehicleState.FAN_MAX)
    }
}
