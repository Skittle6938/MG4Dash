package app.mg4dash

import android.content.ComponentName
import android.provider.Settings
import app.mg4dash.system.HomeSwitcher
import app.mg4dash.system.Rescue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** MG4Dash Rescue 4 link (19/11): what it tells MG4Dash Rescue, the update hand-over, the rescue's check. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RescueHandOverTest {
    private val ours = ComponentName("app.mg4dash", "app.mg4dash.MainActivity")
    private val saic = ComponentName("com.saicmotor.launcher", "com.saicmotor.launcher.view.MainActivity")

    @Test fun homeWordForTheRescue() {
        // SAIC home replaced, or MG4Dash the HOME resolved (SAIC home kept, the owner's car): it is home
        assertEquals("1", HomeSwitcher.homeWord(false, true, saic, ours))
        assertEquals("1", HomeSwitcher.homeWord(false, false, ours, ours))
        // Maintenance mode, or SAIC home in use: not home
        assertEquals("0", HomeSwitcher.homeWord(true, false, ours, ours))
        assertEquals("0", HomeSwitcher.homeWord(false, false, saic, ours))
        // Android's chooser (preference forgotten after a crash loop) or nothing: the last word stays
        assertNull(HomeSwitcher.homeWord(false, false, ComponentName("android", "com.android.internal.app.ResolverActivity"), ours))
        assertNull(HomeSwitcher.homeWord(false, false, ComponentName("com.android.support.car.lenspicker", "com.android.support.car.lenspicker.LensResolverActivity"), ours))
        assertNull(HomeSwitcher.homeWord(false, false, null, ours))
    }

    @Test fun crashGuardServiceModeStaysAuto() {
        // The crash guard's service mode: "auto" (the rescue brings MG4Dash back); the driver's own: "0"
        assertEquals("auto", HomeSwitcher.homeWord(true, false, saic, ours, valetAuto = true))
        assertEquals("0", HomeSwitcher.homeWord(true, false, saic, ours, valetAuto = false))
        // A service mode from before the setting counts as the driver's
        assertEquals("0", HomeSwitcher.homeWord(true, false, saic, ours))
    }

    @Test fun serviceModeLeftAfterRescueOnlyIfAuto() {
        val ctx = RuntimeEnvironment.getApplication()
        val p = app.mg4dash.system.Prefs(ctx)
        val home = HomeSwitcher.currentHome(ctx)
        org.junit.Assume.assumeTrue("HOME resolves to MG4Dash here", home == HomeSwitcher.ours(ctx))
        p.valetMode = true; p.valetAuto = true
        HomeSwitcher.backAfterRescue(ctx)
        assertFalse(p.valetMode); assertFalse(p.valetAuto)
        // The driver's own service mode stays
        p.valetMode = true; p.valetAuto = false
        HomeSwitcher.backAfterRescue(ctx)
        assertTrue(p.valetMode)
    }

    @Test fun wakeOnlyStartsTheBackground() {
        // Woken by the rescue: neither the home nor the service mode changes, no activity opened
        val ctx = RuntimeEnvironment.getApplication()
        val p = app.mg4dash.system.Prefs(ctx)
        p.valetMode = true; p.valetAuto = false
        val homeBefore = HomeSwitcher.currentHome(ctx)
        app.mg4dash.system.WakeReceiver().onReceive(ctx, android.content.Intent(app.mg4dash.system.WakeReceiver.ACTION))
        assertTrue(p.valetMode); assertFalse(p.valetAuto)
        assertEquals(homeBefore, HomeSwitcher.currentHome(ctx))
        assertNull(org.robolectric.Shadows.shadowOf(ctx).nextStartedActivity)
    }

    @Test fun rescueUpdateWaitsWhileItChecks() {
        // Updating the rescue in the middle of its check killed it (car trial, 19/11)
        assertTrue(Rescue.busy("7:100000", 7, 160_000))
        assertFalse("stale", Rescue.busy("7:100000", 7, 100_000 + 5 * 60_000L))
        assertFalse("another boot", Rescue.busy("6:100000", 7, 160_000))
        assertFalse(Rescue.busy(null, 7, 160_000))
        assertFalse(Rescue.busy("garbage", 7, 160_000))
    }

    @Test fun updateHandedOverFromRescue4() {
        assertFalse(Rescue.handsOver(-1))
        assertFalse(Rescue.handsOver(3))
        assertTrue(Rescue.handsOver(4))
        assertTrue(Rescue.handsOver(5))
        assertEquals(8L, Rescue.BUNDLED_VERSION)
    }

    @Test fun rescueRecompiledAndAnsweringAfterItsInstall() {
        val ctx = RuntimeEnvironment.getApplication()
        val before = Rescue.recompile
        var recompiles = 0
        try {
            // It answers at once
            Rescue.recompile = { c, _ -> recompiles++; Settings.Global.putString(c.contentResolver, Rescue.KEY_OK, "4:${System.currentTimeMillis() + 60_000}"); true }
            assertTrue(Rescue.checkInstalled(ctx) { })
            assertEquals(1, recompiles)
            // Silent the first time: recompiled again and asked again
            recompiles = 0
            Settings.Global.putString(ctx.contentResolver, Rescue.KEY_OK, null)
            Rescue.recompile = { c, _ -> recompiles++; if (recompiles == 2) Settings.Global.putString(c.contentResolver, Rescue.KEY_OK, "4:${System.currentTimeMillis() + 60_000}"); true }
            assertTrue(Rescue.checkInstalled(ctx) { })
            assertEquals(2, recompiles)
            // Never answering: two tries, then false
            recompiles = 0
            Settings.Global.putString(ctx.contentResolver, Rescue.KEY_OK, "4:1")
            Rescue.recompile = { _, _ -> recompiles++; true }
            assertFalse(Rescue.checkInstalled(ctx) { })
            assertEquals(2, recompiles)
        } finally { Rescue.recompile = before }
    }

    @Test fun signOfLifeAtTheFirstFrame() {
        val ctx = RuntimeEnvironment.getApplication()
        Settings.Global.putString(ctx.contentResolver, "mg4dash_alive", null)
        val act = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        Rescue.aliveAtFirstFrame(act)
        // (MOCK build: the setting itself is not written; the listener runs without failing)
        act.window.decorView.invalidate()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }
}
