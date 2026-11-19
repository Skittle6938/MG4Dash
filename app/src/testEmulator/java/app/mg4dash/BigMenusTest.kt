package app.mg4dash

import android.os.Looper
import android.view.View
import android.widget.ScrollView
import app.mg4dash.system.Prefs
import app.mg4dash.ui.Popups
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** Grand affichage, step 6: menus within the screen (1442 × 540 dp), their lists scroll. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class BigMenusTest {
    private val prefs get() = Prefs(RuntimeEnvironment.getApplication())
    private fun idle() = repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }

    private fun home(): MainActivity {
        prefs.raw.edit().putBoolean("vertical_dock", true).putBoolean("tutorial_shortcuts_seen", true).putInt("home_style", Prefs.STYLE_BIG).commit()
        return Robolectric.buildActivity(MainActivity::class.java).setup().get().also {
            idle()
            val root = it.window.decorView
            root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1920, 720)
        }
    }

    private fun latest() = shadowOf(RuntimeEnvironment.getApplication()).latestPopupWindow

    @Test fun longChoiceMenuScrollsWithinTheScreen() {
        val a = home()
        val (_, h) = Popups.room(a)
        app.mg4dash.ui.SourceMenu.choose(a, a.window.decorView, (1..14).map { "Choix $it" }, 2) {}
        idle()
        val p = latest()
        assertTrue("menu ${p.height} within $h", p.height <= h)
        assertTrue("its list scrolls", p.contentView is ScrollView)
    }

    @Test fun layoutMenuWithinTheScreen() {
        val a = home()
        val (w, h) = Popups.room(a)
        app.mg4dash.ui.LayoutPicker.show(a, a.window.decorView, app.mg4dash.ui.HomeSlots.Layout.BIG_QUAD) {}
        idle()
        val p = latest()
        assertTrue("layout menu ${p.width} × ${p.height} within $w × $h", p.width <= w && p.height <= h)
    }

    @Test fun confirmationWithinTheScreen() {
        val a = home()
        val (w, h) = Popups.room(a)
        app.mg4dash.ui.SourceMenu.confirm(a, a.window.decorView, "Remettre la disposition et les cartes d'origine ? ".repeat(8), "OK") {}
        idle()
        val p = latest()
        assertTrue("confirmation ${p.width} × ${p.height} within $w × $h", p.width <= w && p.height <= h)
    }

    /** A short menu is left as it is. */
    @Test fun shortMenuUnchanged() {
        val a = home()
        app.mg4dash.ui.SourceMenu.choose(a, a.window.decorView, listOf("Un", "Deux"), 0) {}
        idle()
        assertTrue(latest().contentView !is ScrollView)
    }
}
