package app.mg4dash

import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import app.mg4dash.system.Prefs
import app.mg4dash.ui.HomeSlots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** Grand affichage, step 3: the cards take the form of their place, and go back to it when they move (edit mode). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class BigFormsTest {
    private val prefs get() = Prefs(RuntimeEnvironment.getApplication())
    private fun idle() = repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }

    private fun home(layout: HomeSlots.Layout, spec: String): MainActivity {
        prefs.raw.edit().clear().putBoolean("vertical_dock", true).putBoolean("tutorial_shortcuts_seen", true).putInt("home_style", Prefs.STYLE_BIG)
            .putString("home_layout_big", layout.key).putString("slots_" + layout.key, spec).commit()
        return Robolectric.buildActivity(MainActivity::class.java).setup().get().also { idle() }
    }

    private fun <T> field(a: MainActivity, name: String): T {
        @Suppress("UNCHECKED_CAST")
        return MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(a) as T
    }

    private fun apply(a: MainActivity, layout: HomeSlots.Layout, spec: String) {
        HomeSlots.save(prefs, HomeSlots.decode(layout, spec))
        MainActivity::class.java.getDeclaredMethod("applySlots").apply { isAccessible = true }.invoke(a)
        idle()
    }

    @Test fun climateInEachForm() {
        val a = home(HomeSlots.Layout.BIG_QUAD, "tl=climate;bl=energy;tr=media;br=hourly")
        val b = field<app.mg4dash.databinding.ActivityMainBinding>(a, "b")
        val card = b.climateCard
        val built = (0 until card.childCount).map { card.getChildAt(it) }
        // A quarter: the essentials and More
        assertEquals(View.GONE, b.climDirRow.visibility)
        assertEquals(View.GONE, b.chipEco.visibility)
        val plus = field<android.widget.TextView>(a, "climatePlus")
        assertEquals(View.VISIBLE, plus.visibility)
        assertEquals("PLUS", plus.text.toString().uppercase())
        // The same card moved to a band (two bands): two lines
        apply(a, HomeSlots.Layout.BIG_QUAD, "tl=energy;bl=climate;tr=media;br=hourly")
        // (still a quarter there) then the two bands' layout for the band
        prefs.homeLayoutBig = HomeSlots.Layout.BIG_TWO_BANDS.key
        val bands = home(HomeSlots.Layout.BIG_TWO_BANDS, "t=climate;b=media")
        val bb = field<app.mg4dash.databinding.ActivityMainBinding>(bands, "b")
        assertNotSame(bb.climateCard, bb.climDirRow.parent)
        assertSame((bb.tempDown.parent as View).parent, (bb.fanDown.parent as View).parent)
        assertSame(bb.climDirRow.parent, bb.climPower.parent.parent)
        assertEquals(View.VISIBLE, bb.chipEco.visibility)
        // Back in a half: the card as built, all its buttons
        apply(bands, HomeSlots.Layout.BIG_TWO_BANDS, "t=climate;b=media")
        val half = home(HomeSlots.Layout.BIG_TWO, "l=climate;r=media")
        val hb = field<app.mg4dash.databinding.ActivityMainBinding>(half, "b")
        assertSame(hb.climateCard, hb.climDirRow.parent)
        assertEquals(View.VISIBLE, hb.climDirRow.visibility)
        assertTrue(built.size == hb.climateCard.childCount)
    }

    /** Moved from a band to a quarter in the edit mode (no rebuild): the lines come back, then the quarter's form. */
    @Test fun bandThenQuarterInTheSameHome() {
        val a = home(HomeSlots.Layout.BIG_BAND_TOP, "t=climate;bl=energy,hourly,daily;br=media")
        val b = field<app.mg4dash.databinding.ActivityMainBinding>(a, "b")
        val card = b.climateCard
        assertNotSame(card, b.climDirRow.parent)
        apply(a, HomeSlots.Layout.BIG_BAND_TOP, "t=energy,hourly;bl=climate,daily;br=media")
        assertSame(card, b.climDirRow.parent)
        assertSame(card, (b.tempDown.parent as View).parent)
        assertEquals(View.GONE, b.climDirRow.visibility)
        assertEquals(View.VISIBLE, field<android.widget.TextView>(a, "climatePlus").visibility)
        // The energy in the band: its own numbers again; in a quarter, the compact ones
        val big = b.battery.textSize
        apply(a, HomeSlots.Layout.BIG_BAND_TOP, "t=climate,hourly;bl=energy,daily;br=media")
        assertTrue("compact battery ${b.battery.textSize} < $big", b.battery.textSize < big)
        assertTrue((b.climPower.parent as LinearLayout).parent !== card)
    }

    @Test fun shortcutsGridPerForm() {
        val g = app.mg4dash.shortcuts.Shortcuts::class.java
        fun grid(size: HomeSlots.Size, d: Int) = app.mg4dash.shortcuts.Shortcuts.grid(size, d)
        assertEquals(3 to 1, grid(HomeSlots.Size.QUARTER, 0))
        assertEquals(4 to 4, grid(HomeSlots.Size.HALF, 2))
        assertEquals(8 to 2, grid(HomeSlots.Size.BAND, 2))
        assertTrue(g.name.isNotEmpty())
    }
}
