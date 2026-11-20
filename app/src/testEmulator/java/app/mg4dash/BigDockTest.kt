package app.mg4dash

import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import app.mg4dash.system.Prefs
import app.mg4dash.ui.BigDisplay
import app.mg4dash.ui.Dock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** Grand affichage, step 4: the bar on the driver's side, its six buttons bigger, MG's fog button zone in pixels. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1920dp-h720dp-land-mdpi")
class BigDockTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private val prefs get() = Prefs(ctx)

    private fun home(): MainActivity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        .also { repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) } }

    @Test fun bottomBarGoesToTheSideInTheMode() {
        prefs.raw.edit().putBoolean("vertical_dock", false).putBoolean("tutorial_shortcuts_seen", true).putInt("home_style", Prefs.STYLE_BIG).commit()
        assertTrue(Dock.vertical(ctx))
        val a = home()
        val bar = a.findViewById<LinearLayout>(R.id.dock)
        assertEquals(LinearLayout.VERTICAL, bar.orientation)
        // 72 dp, enlarged
        assertEquals(Math.round(72 * BigDisplay.DPI / 160f).toFloat(), bar.layoutParams.width.toFloat(), 1f)
        // Back to the other styles: at the bottom again, as chosen
        prefs.homeStyle = 0
        assertFalse(Dock.vertical(ctx))
        assertTrue("published to MG4Radio as it is shown", app.mg4dash.ui.Themes.published(ctx).split(',')[3] == "0")
        prefs.homeStyle = Prefs.STYLE_BIG
        assertTrue(app.mg4dash.ui.Themes.published(ctx).split(',')[3] == "1")
    }

    @Test fun sixButtonsKept() {
        prefs.raw.edit().putBoolean("vertical_dock", true).putBoolean("tutorial_shortcuts_seen", true).putInt("home_style", Prefs.STYLE_BIG).commit()
        val bar = home().findViewById<LinearLayout>(R.id.dock)
        fun clickable(v: View): Int = if (v.isClickable || v.isLongClickable) 1 else if (v is android.view.ViewGroup) (0 until v.childCount).sumOf { clickable(v.getChildAt(it)) } else 0
        assertTrue("six buttons: ${clickable(bar)}", clickable(bar) >= 6)
    }

    /** The wide bar: half as wide again in the mode (×2 would eat the screen), ×2 elsewhere. */
    @Test fun wideBarNarrowerInTheMode() {
        prefs.raw.edit().putBoolean("vertical_dock", true).putBoolean("wide_bar", true).putBoolean("tutorial_shortcuts_seen", true).putInt("home_style", Prefs.STYLE_BIG).commit()
        val big = home()
        assertEquals(108, Dock.barDp(big))
        assertEquals(Math.round(108 * BigDisplay.DPI / 160f).toFloat(), big.findViewById<View>(R.id.dock).layoutParams.width.toFloat(), 1f)
        prefs.homeStyle = 0
        assertEquals(144, Dock.barDp(home()))
    }

    /** MG's fog button keeps its pixels: the bar's top under it is 68 px high whatever the mode. */
    @Test fun fogZoneInPixels() {
        prefs.raw.edit().putBoolean("vertical_dock", true).putBoolean("tutorial_shortcuts_seen", true).putInt("home_style", Prefs.STYLE_BIG).commit()
        val a = home()
        assertTrue(Dock.fogOnBar(Dock.vertical(a), Dock.rightSide(a)))
        if (Dock.fogShown(a)) {
            val bar = a.findViewById<LinearLayout>(R.id.dock)
            assertEquals(68, bar.getChildAt(0).layoutParams.height)
        }
        assertEquals(76f, BigDisplay.unscaled(a, 76) * a.resources.displayMetrics.density, 1f)
    }
}
