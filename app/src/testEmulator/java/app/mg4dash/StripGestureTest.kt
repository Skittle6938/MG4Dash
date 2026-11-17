package app.mg4dash

import android.app.Activity
import android.content.pm.PackageInfo
import android.os.SystemClock
import android.view.MotionEvent
import app.mg4dash.system.Firmware.Gen
import app.mg4dash.ui.LinkContract
import app.mg4dash.ui.StripGesture
import app.mg4dash.ui.StripGesture.Action
import app.mg4dash.ui.StripGesture.Mode
import app.mg4dash.ui.TwoFingerPull
import app.mg4dash.ui.stripMode
import app.mg4dash.ui.stripAction
import app.mg4dash.ui.stripState
import app.mg4dash.ui.StripState
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
 * Gestures from the top strip (17/11): one finger the menu, two fingers MG4Dash's home, three fingers MG4Link's
 * disconnection; when the strip catches them; the two finger pull of MG4Dash's own screens; the contract with MG4Link.
 * Density 1 here, as on the car: dp = px. Two and three fingers need 120 px.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StripGestureTest {
    private fun g() = StripGesture(1f)

    // ── Fingers ──

    @Test fun oneFingerDownIsTheMenu() {
        val s = g(); s.down(500f, 5f)
        assertEquals(Action.NONE, s.move(502f, 20f, 1))      // not far enough yet
        assertEquals(Action.MENU, s.move(505f, 40f, 1))
        assertTrue(s.menu)
        assertEquals(Action.NONE, s.move(505f, 80f, 1))      // once per touch
    }

    @Test fun twoFingersDownAreHome() {
        val s = g(); s.down(500f, 5f)
        s.pointerDown(2)
        assertEquals(Action.NONE, s.move(500f, 100f, 2))     // under 120 dp
        assertEquals(Action.HOME, s.move(505f, 130f, 2))
        assertEquals(Action.NONE, s.move(505f, 200f, 2))
    }

    @Test fun threeFingersDownDisconnect() {
        val s = g(); s.down(500f, 5f)
        s.pointerDown(2); s.pointerDown(3)
        assertEquals(Action.DISCONNECT, s.move(500f, 130f, 3))
    }

    @Test fun aThirdFingerBeforeTheThresholdMakesItThree() {
        val s = g(); s.down(500f, 5f)
        s.pointerDown(2)
        assertEquals(Action.NONE, s.move(500f, 80f, 2))       // a third finger a little late is still counted
        s.pointerDown(3)
        assertEquals(Action.DISCONNECT, s.move(500f, 130f, 3))
    }

    @Test fun aFingerLiftedEarlyStillCountsTheMostFingers() {
        val s = g(); s.down(500f, 5f)
        s.pointerDown(3)
        assertEquals(Action.DISCONNECT, s.move(500f, 130f, 2))
    }

    @Test fun aSecondFingerAfterTheMenuGivesItBackAndBecomesHome() {
        val s = g(); s.down(500f, 5f)
        assertEquals(Action.MENU, s.move(500f, 30f, 1))
        assertTrue(s.pointerDown(2))                          // the menu goes back
        assertFalse(s.menu)
        assertEquals(Action.HOME, s.move(500f, 130f, 2))
    }

    @Test fun sidewaysOrUpIsNothing() {
        val s = g(); s.down(500f, 5f); s.pointerDown(2)
        assertEquals(Action.NONE, s.move(700f, 130f, 2))      // more sideways than down
        assertEquals(Action.NONE, s.move(500f, -140f, 2))
        val one = g(); one.down(500f, 5f)
        assertEquals(Action.NONE, one.move(560f, 40f, 1))     // slanted
    }

    @Test fun anewAtEachTouch() {
        val s = g(); s.down(500f, 5f); s.pointerDown(2)
        assertEquals(Action.HOME, s.move(500f, 130f, 2))
        s.up()
        s.down(500f, 5f)
        assertEquals(Action.MENU, s.move(500f, 40f, 1))
    }

    // ── When the strip catches ──

    @Test fun stripModeByApp() {
        // Another app in front: always caught (menu, home, disconnect)
        assertEquals(Mode.CATCH, stripMode(linkInFront = false, immersive = true, gen = Gen.SWI69, carPulldown = true))
        assertEquals(Mode.CATCH, stripMode(false, null, Gen.SWI173, false))
        // MG4Link full screen: left to MG4Link, which pulls the menu itself
        assertEquals(Mode.PASS, stripMode(true, true, Gen.SWI173, false))
        // MG4Link under the car's bar: caught
        assertEquals(Mode.CATCH, stripMode(true, false, Gen.SWI173, false))
        assertEquals(Mode.CATCH, stripMode(true, false, Gen.SWI69, false))
        // MG4Link's option for the car's pull-down: the top is the car's
        assertEquals(Mode.PASS, stripMode(true, false, Gen.SWI173, true))
        // No word from an older MG4Link: as before (caught on SWI173 only)
        assertEquals(Mode.CATCH, stripMode(true, null, Gen.SWI173, false))
        assertEquals(Mode.PASS, stripMode(true, null, Gen.SWI69, false))
    }

    @Test fun threeFingersOnlyOverMg4Link() {
        assertEquals(Action.DISCONNECT, stripAction(Action.DISCONNECT, linkInFront = true))
        assertEquals(Action.NONE, stripAction(Action.DISCONNECT, linkInFront = false))
        // The others wherever they are
        assertEquals(Action.HOME, stripAction(Action.HOME, linkInFront = false))
        assertEquals(Action.MENU, stripAction(Action.MENU, linkInFront = true))
    }

    @Test fun stripStateHeightAndCatching() {
        // MG4Link under the car's bar: the bar exactly, caught
        assertEquals(StripState(80, true), stripState(true, false, Gen.SWI173, carPulldown = false, barPx = 80))
        // Its car pull-down option on: the thin band, let through (the top is the car's menu)
        assertEquals(StripState(12, false), stripState(true, false, Gen.SWI173, carPulldown = true, barPx = 80))
        // Full screen: the thin band, let through
        assertEquals(StripState(12, false), stripState(true, true, Gen.SWI173, carPulldown = false, barPx = 80))
        // Another app: the thin band, caught, whatever MG4Link's option
        assertEquals(StripState(12, true), stripState(false, false, Gen.SWI173, carPulldown = true, barPx = 80))
        // Bar height unknown: never more than the thin band
        assertEquals(StripState(12, true), stripState(true, false, Gen.SWI173, carPulldown = false, barPx = 0))
    }

    // ── MG4Dash's own screens: two fingers from the top ──

    private fun ev(down: Long, action: Int, vararg pts: Pair<Float, Float>): MotionEvent {
        val props = Array(pts.size) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(pts.size) { i -> MotionEvent.PointerCoords().apply { x = pts[i].first; y = pts[i].second; pressure = 1f; size = 1f } }
        return MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, pts.size, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
    }

    @Test fun twoFingerPullOnMg4DashScreens() {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val pull = TwoFingerPull(a)
        val t = SystemClock.uptimeMillis()
        var cancelled = 0
        val cancel: (MotionEvent) -> Unit = { if (it.actionMasked == MotionEvent.ACTION_CANCEL) cancelled++ }
        assertFalse(pull.onTouch(ev(t, MotionEvent.ACTION_DOWN, 300f to 20f), cancel))
        assertFalse(pull.onTouch(ev(t, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 300f to 20f, 500f to 30f), cancel))
        assertFalse(pull.onTouch(ev(t, MotionEvent.ACTION_MOVE, 300f to 80f, 500f to 90f), cancel))
        // Both fingers down far enough: the gesture takes the touch from the widgets
        assertTrue(pull.onTouch(ev(t, MotionEvent.ACTION_MOVE, 305f to 150f, 505f to 160f), cancel))
        assertEquals(1, cancelled)
        assertTrue(pull.onTouch(ev(t, MotionEvent.ACTION_MOVE, 305f to 200f, 505f to 210f), cancel))
    }

    @Test fun twoFingerPullStartingLowIsNothing() {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val pull = TwoFingerPull(a)
        val t = SystemClock.uptimeMillis()
        val none: (MotionEvent) -> Unit = {}
        pull.onTouch(ev(t, MotionEvent.ACTION_DOWN, 300f to 300f), none)
        pull.onTouch(ev(t, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 300f to 300f, 500f to 300f), none)
        assertFalse(pull.onTouch(ev(t, MotionEvent.ACTION_MOVE, 300f to 500f, 500f to 500f), none))
    }

    // ── Contract with MG4Link ──

    /**
     * MG4Link declares the signature permission com.allgo.app.androidauto.permission.USER_DISCONNECT and requires it on
     * its receiver; MG4Dash only uses it (manifest). Without MG4Link the permission is unknown, which Android ignores,
     * and nothing is sent. Both apps carry the same platform key, so it is granted once MG4Link is there.
     */
    @Test fun disconnectIsAnExplicitBroadcastToMg4Link() {
        val c = RuntimeEnvironment.getApplication()
        // No MG4Link: nothing sent
        assertFalse(LinkContract.sendDisconnect(c))
        assertTrue(shadowOf(c).broadcastIntents.none { it.action == LinkContract.ACTION_USER_DISCONNECT })
        shadowOf(c.packageManager).installPackage(PackageInfo().apply { packageName = LinkContract.PACKAGE })
        assertTrue(LinkContract.sendDisconnect(c))
        val sent = shadowOf(c).broadcastIntents.last()
        assertEquals("com.allgo.app.androidauto.USER_DISCONNECT", sent.action)
        assertEquals("com.allgo.app.androidauto", sent.`package`)
        assertNull(sent.extras)
    }

    @Test fun mg4LinkPermissionRequested() {
        val c = RuntimeEnvironment.getApplication()
        val info = c.packageManager.getPackageInfo(c.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        assertTrue("com.allgo.app.androidauto.permission.USER_DISCONNECT" in info.requestedPermissions.orEmpty())
    }

    @Test fun carPulldownReadFromMg4LinkSetting() {
        val c = RuntimeEnvironment.getApplication()
        assertFalse(LinkContract.carPulldown(c))
        android.provider.Settings.Global.putInt(c.contentResolver, "mg4link_car_pulldown", 1)
        assertTrue(LinkContract.carPulldown(c))
        android.provider.Settings.Global.putInt(c.contentResolver, "mg4link_car_pulldown", 0)
        assertFalse(LinkContract.carPulldown(c))
    }
}
