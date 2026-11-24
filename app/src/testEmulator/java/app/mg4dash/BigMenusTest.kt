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

    /** A choice is a window of its own over the screen (24/11): within it, its long list scrolling. */
    @Test fun longChoiceMenuScrollsWithinTheScreen() {
        val a = home()
        val (_, h) = Popups.room(a)
        app.mg4dash.ui.SourceMenu.choose(a, a.window.decorView, (1..14).map { "Choix $it" }, 2) {}
        idle()
        val (height, scroll) = choiceWindow(a)
        assertTrue("window $height within $h", height <= h)
        assertTrue("its list scrolls", scroll != null && scroll.getChildAt(0).height > scroll.height)
    }

    /** The latest window laid out on the screen: its height, and its scrolling list. */
    private fun choiceWindow(a: MainActivity): Pair<Int, ScrollView?> {
        val win = org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!
        val dv = win.decorView
        dv.measure(View.MeasureSpec.makeMeasureSpec(win.attributes.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(a.window.decorView.height, View.MeasureSpec.AT_MOST))
        dv.layout(0, 0, dv.measuredWidth, dv.measuredHeight)
        fun all(v: View): List<ScrollView> = listOfNotNull(v as? ScrollView) + ((v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
        fun find(v: View): ScrollView? = all(v).filter { it.childCount > 0 }.maxByOrNull { it.getChildAt(0).height }
        return dv.measuredHeight to find(dv)
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
        // A window now (same confirmation everywhere, 24/11): its width and height within the screen
        val win = org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!
        val dv = win.decorView
        dv.measure(android.view.View.MeasureSpec.makeMeasureSpec(win.attributes.width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
        assertTrue("confirmation ${win.attributes.width} × ${dv.measuredHeight} within $w × $h", win.attributes.width <= w && dv.measuredHeight <= h)
    }

    /** A short choice: no scrolling needed. */
    @Test fun shortMenuUnchanged() {
        val a = home()
        app.mg4dash.ui.SourceMenu.choose(a, a.window.decorView, listOf("Un", "Deux"), 0) {}
        idle()
        val (_, scroll) = choiceWindow(a)
        assertTrue(scroll == null || scroll.getChildAt(0).height <= scroll.height)
    }
}
