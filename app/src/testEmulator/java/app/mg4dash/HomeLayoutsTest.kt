package app.mg4dash

import android.os.Looper
import android.view.View
import app.mg4dash.system.Prefs
import app.mg4dash.ui.HomeSlots
import app.mg4dash.ui.HomeSlots.Card
import app.mg4dash.ui.HomeSlots.Fill
import app.mg4dash.ui.HomeSlots.Layout
import app.mg4dash.ui.HomeSlots.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** The eight layouts of the shared grid (#38): five sizes of places, the big ones only for the cards that fill them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1920dp-h720dp-land-mdpi")
class HomeLayoutsTest {
    private val prefs get() = Prefs(RuntimeEnvironment.getApplication())

    @Test fun everyLayoutStartsWithAFullHome() {
        Layout.entries.forEach { layout ->
            val c = HomeSlots.load(prefs, layout)
            assertEquals("${layout.key}: one id per place", layout.slots.size, layout.slots.map { it.id }.toSet().size)
            layout.slots.forEach { s ->
                val f = c.fill(s.id)
                if (f is Fill.Cards) assertTrue("${layout.key}/${s.id} has a card", f.cards.isNotEmpty())
                else assertEquals("${layout.key}/${s.id}: climate and media in a medium place", Size.MEDIUM, s.size)
                f.let { it as? Fill.Cards }?.cards?.forEach { card -> assertTrue("${layout.key}/${s.id}: $card fits", HomeSlots.fits(card, s.size)) }
            }
            assertNull("${layout.key}: driving only where the driver puts it", c.slotOf(Card.DRIVING))
        }
    }

    @Test fun fiveSizesOnOneGrid() {
        val sizes = mutableMapOf<Size, MutableSet<Pair<Int, Int>>>()
        Layout.entries.forEach { layout ->
            val cells = layout.cells(12f / 1888, 12f / 708)
            layout.slots.forEach { s ->
                val r = cells.getValue(s.id)
                assertTrue("${layout.key}/${s.id} inside", r.left >= -1e-4f && r.top >= -1e-4f && r.right <= 1.0001f && r.bottom <= 1.0001f)
                sizes.getOrPut(s.size) { mutableSetOf() } += Math.round(r.width() * 1888) to Math.round(r.height() * 708)
            }
            // No place over another
            val all = layout.slots.map { cells.getValue(it.id) }
            all.forEachIndexed { i, a -> all.drop(i + 1).forEach { b -> assertTrue("${layout.key}: no overlap", !android.graphics.RectF.intersects(a, b)) } }
        }
        // The same size everywhere it is used
        sizes.forEach { (size, dims) -> assertEquals("$size: $dims", 1, dims.size) }
        assertEquals(5, sizes.size)
        val p = sizes.getValue(Size.SMALL).single(); val g = sizes.getValue(Size.LARGE).single()
        assertTrue("a big square is two columns and a gap: $g vs $p", Math.abs(2 * p.first + 12 - g.first) <= 1)
    }

    @Test fun bigPlacesOnlyForTheCardsThatFillThem() {
        val c = HomeSlots.load(prefs, Layout.SQUARE_LEFT)
        assertEquals(HomeSlots.Refusal.TOO_BIG, HomeSlots.toggle(c, "g", Card.ENERGY).refused)
        val ok = HomeSlots.toggle(c, "g", Card.DRIVING)
        assertNull(ok.refused)
        assertTrue(Card.DRIVING in ok.config.cards("g"))
        // A card stored in a place too big for it is left out there
        assertEquals(listOf(Card.MAP), HomeSlots.decode(Layout.BAND_TOP, "w=map,energy;tr=climate").cards("w"))
        // In a small or medium place, everything goes
        assertTrue(Card.entries.all { HomeSlots.fits(it, Size.SMALL) && HomeSlots.fits(it, Size.TALL) })
    }

    @Test fun layoutChosenAndOldMapStyleKept() {
        prefs.raw.edit().clear().putInt("home_style", 2).commit()
        assertEquals(Layout.MAP, HomeSlots.layoutOf(prefs))
        HomeSlots.choose(prefs, Layout.BAND_BOTTOM)
        assertEquals(Layout.BAND_BOTTOM, HomeSlots.layoutOf(prefs))
        assertEquals(0, prefs.homeStyle)
        HomeSlots.choose(prefs, Layout.FULL)
        assertEquals(Layout.FULL, HomeSlots.layoutOf(prefs))
    }

    /** On screen: a place two columns wide lines up with two places and the gap between them. */
    @Test fun spansLineUpOnScreen() {
        fun boxes(layout: Layout): Map<String, View> {
            prefs.raw.edit().clear().putBoolean("vertical_dock", true).putString("home_layout", layout.key).commit()
            val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
            @Suppress("UNCHECKED_CAST")
            val frames = MainActivity::class.java.getDeclaredField("frames").apply { isAccessible = true }.get(act) as Map<String, Any>
            return frames.mapValues { (_, f) -> f.javaClass.getDeclaredField("box").apply { isAccessible = true }.get(f) as View }
        }
        val gap = 12
        val full = boxes(Layout.FULL)
        val p = full.getValue("bl").width
        val sq = boxes(Layout.SQUARE_LEFT)
        assertTrue("big square ${sq.getValue("g").width} vs ${2 * p + gap}", Math.abs(sq.getValue("g").width - (2 * p + gap)) <= 1)
        assertTrue(Math.abs(full.getValue("tl").height - sq.getValue("tr").height) <= 1)
        val band = boxes(Layout.BAND_TOP)
        assertTrue("band ${band.getValue("w").width} vs ${2 * p + gap}", Math.abs(band.getValue("w").width - (2 * p + gap)) <= 1)
        assertTrue(Math.abs(band.getValue("bl").width - p) <= 1)
        assertTrue("band height as a medium place", Math.abs(band.getValue("w").height - full.getValue("tl").height) <= 1)
    }
}
