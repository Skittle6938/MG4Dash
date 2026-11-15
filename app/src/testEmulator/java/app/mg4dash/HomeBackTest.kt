package app.mg4dash

import android.content.ComponentName
import app.mg4dash.system.HomeSwitcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** MG4Dash set as home again after an update dropped the preference (15/11), only when the driver chose it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HomeBackTest {
    private val ours = ComponentName("app.mg4dash", "app.mg4dash.MainActivity")
    private val chooser = ComponentName("com.android.support.car.lenspicker", "com.android.support.car.lenspicker.LensResolverActivity")

    @Test fun backAfterTheChooser() {
        assertTrue(HomeSwitcher.needsHomeBack(valet = false, stockDisabled = true, current = chooser, ours = ours))
        assertTrue("nothing resolved", HomeSwitcher.needsHomeBack(false, true, null, ours))
    }

    @Test fun leftAloneOtherwise() {
        assertFalse("already home", HomeSwitcher.needsHomeBack(false, true, ours, ours))
        assertFalse("maintenance mode: SAIC home on purpose", HomeSwitcher.needsHomeBack(true, true, chooser, ours))
        assertFalse("SAIC home never replaced: MG4Dash used as an app", HomeSwitcher.needsHomeBack(false, false, chooser, ours))
    }
}
