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
                else assertTrue("${layout.key}/${s.id}: the media in a medium or tall place", HomeSlots.mediaFits(s.size))
                f.let { it as? Fill.Cards }?.cards?.forEach { card -> assertTrue("${layout.key}/${s.id}: $card fits", HomeSlots.fits(card, s.size)) }
            }
            assertNull("${layout.key}: driving only where the driver puts it", c.slotOf(Card.DRIVING))
        }
    }

    @Test fun fiveSizesOnOneGrid() {
        val sizes = mutableMapOf<Size, MutableSet<Pair<Int, Int>>>()
        Layout.of(big = false).forEach { layout ->
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
        // In a small or tall place, everything goes but the climate (medium places only, as before)
        assertTrue(Card.entries.filter { it != Card.CLIMATE }.all { HomeSlots.fits(it, Size.SMALL) && HomeSlots.fits(it, Size.TALL) })
        assertTrue(HomeSlots.fits(Card.CLIMATE, Size.MEDIUM) && !HomeSlots.fits(Card.CLIMATE, Size.SMALL))
    }

    /** The owner's matrix (10 Nov 2026): what each size of place offers. */
    @Test fun compatibilityMatrix() {
        val all = Size.entries.filter { !it.big }
        fun only(card: Card, vararg sizes: Size) = all.forEach { s -> assertEquals("$card in $s", s in sizes, HomeSlots.fits(card, s)) }
        only(Card.CLIMATE, Size.MEDIUM, Size.TALL)
        listOf(Card.MAP, Card.WEB, Card.CONSUMPTION, Card.SHORTCUTS, Card.DRIVING).forEach { only(it, *all.toTypedArray()) }
        listOf(Card.ENERGY, Card.TRIP, Card.POWER, Card.PHONE, Card.HOURLY, Card.DAILY, Card.TRIPS).forEach { only(it, Size.SMALL, Size.MEDIUM, Size.TALL) }
        assertEquals(listOf(Size.MEDIUM, Size.TALL), all.filter { HomeSlots.mediaFits(it) })
    }

    @Test fun aCardStoredWhereItNoLongerFitsMovesOrIsLeftOut() {
        // The energy stored in the big square: moved to the first place that takes it
        val c = HomeSlots.decode(Layout.SQUARE_LEFT, "g=map,energy;tr=climate;br=trip")
        assertEquals(listOf(Card.MAP), c.cards("g"))
        assertEquals("tr", c.slotOf(Card.ENERGY))
        // The media into the tall place: the cards there go where the media was, those that fit there
        val moved = HomeSlots.setKind(HomeSlots.defaults(Layout.MAP_CENTER), "c", HomeSlots.Kind.MEDIA).config
        assertEquals(Fill.Media, moved.fill("c"))
        assertEquals(listOf(Card.MAP, Card.CONSUMPTION, Card.SHORTCUTS), moved.cards("tr"))
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

    // ── Grand affichage (study v4, step 2): seven layouts on a grid of two columns ──

    @Test fun sevenBigLayoutsOnTwoColumns() {
        assertEquals(listOf("big_quad", "big_left", "big_right", "big_band_bottom", "big_band_top", "big_two", "big_two_bands"), Layout.of(big = true).map { it.key })
        assertEquals(8, Layout.of(big = false).size)
        val sizes = mutableMapOf<Size, MutableSet<Pair<Int, Int>>>()
        val w = 1330; val h = 516; val gap = 12
        Layout.of(big = true).forEach { layout ->
            assertEquals(2, layout.columns)
            val cells = layout.cells(gap.toFloat() / w, gap.toFloat() / h)
            layout.slots.forEach { s ->
                assertTrue("${layout.key}/${s.id} is a size of the mode", s.size.big)
                val r = cells.getValue(s.id)
                assertTrue("${layout.key}/${s.id} inside", r.left >= -1e-4f && r.top >= -1e-4f && r.right <= 1.0001f && r.bottom <= 1.0001f)
                // On the two columns: starts on the left edge or on the second column
                val x = Math.round(r.left * w)
                assertTrue("${layout.key}/${s.id} at x $x", x == 0 || x == (w - gap) / 2 + gap)
                sizes.getOrPut(s.size) { mutableSetOf() } += Math.round(r.width() * w) to Math.round(r.height() * h)
            }
            val all = layout.slots.map { cells.getValue(it.id) }
            all.forEachIndexed { i, a -> all.drop(i + 1).forEach { b -> assertTrue("${layout.key}: no overlap", !android.graphics.RectF.intersects(a, b)) } }
            // The places fill the screen: nothing left empty
            val area = all.sumOf { (it.width() * it.height()).toDouble() }
            assertTrue("${layout.key}: fills the screen ($area)", area > 0.95)
        }
        sizes.forEach { (size, dims) -> assertEquals("$size: $dims", 1, dims.size) }
        assertEquals(setOf(Size.QUARTER, Size.HALF, Size.BAND), sizes.keys)
        val q = sizes.getValue(Size.QUARTER).single(); val half = sizes.getValue(Size.HALF).single(); val band = sizes.getValue(Size.BAND).single()
        assertTrue("a band is two quarters and a gap: $band vs $q", Math.abs(2 * q.first + gap - band.first) <= 1 && band.second == q.second)
        assertTrue("a half is two quarters high: $half vs $q", half.first == q.first && Math.abs(2 * q.second + gap - half.second) <= 1)
    }

    /** The starting contents of the study. */
    @Test fun bigStartingContents() {
        fun d(l: Layout) = HomeSlots.defaults(l).encode()
        assertEquals("tl=climate;bl=energy,trip,power,phone;tr=media;br=hourly,daily", d(Layout.BIG_QUAD))
        assertEquals("l=map,consumption,shortcuts;tr=climate,energy,hourly,daily;br=media", d(Layout.BIG_LEFT))
        assertEquals("tl=climate,energy,hourly,daily;bl=media;r=map,consumption,shortcuts", d(Layout.BIG_RIGHT))
        assertEquals("tl=climate;tr=energy,trip,power,phone,hourly,daily;b=media", d(Layout.BIG_BAND_BOTTOM))
        assertEquals("t=map,consumption,shortcuts;bl=climate,energy,hourly,daily;br=media", d(Layout.BIG_BAND_TOP))
        assertEquals("l=climate,energy,hourly,daily;r=media", d(Layout.BIG_TWO))
        assertEquals("t=climate;b=media", d(Layout.BIG_TWO_BANDS))
    }

    /** Every card in every place of the mode, the media too (alone in its place, as always). */
    @Test fun everyCardInEveryBigPlace() {
        for (size in listOf(Size.QUARTER, Size.HALF, Size.BAND)) {
            assertTrue(Card.entries.all { HomeSlots.fits(it, size) })
            assertTrue(HomeSlots.mediaFits(size))
        }
        val c = HomeSlots.load(prefs, Layout.BIG_QUAD)
        // The climate in the weather's quarter: the only card of its own place, it stays there
        assertEquals(HomeSlots.Refusal.ONLY_CARD_THERE, HomeSlots.toggle(c, "br", Card.CLIMATE).refused)
        val ok = HomeSlots.toggle(c, "br", Card.TRIP)
        assertNull(ok.refused)
        assertEquals("br", ok.config.slotOf(Card.TRIP))
        assertTrue("one place per card", Card.TRIP !in ok.config.cards("bl"))
        // A second card in the climate's quarter, and then the climate can move
        val two = HomeSlots.toggle(ok.config, "tl", Card.POWER).config
        assertEquals("br", HomeSlots.toggle(two, "br", Card.CLIMATE).config.slotOf(Card.CLIMATE))
    }

    /** One layout remembered per mode: going to Grand affichage and back loses nothing, each layout keeps its cards. */
    @Test fun oneLayoutPerMode() {
        prefs.raw.edit().clear().commit()
        HomeSlots.choose(prefs, Layout.MAP_CENTER)
        val normal = HomeSlots.toggle(HomeSlots.load(prefs, Layout.MAP_CENTER), "tl", Card.TRIPS).config
        HomeSlots.save(prefs, normal)
        prefs.homeStyle = Prefs.STYLE_BIG
        assertEquals("Four by default", Layout.BIG_QUAD, HomeSlots.layoutOf(prefs))
        HomeSlots.choose(prefs, Layout.BIG_TWO)
        assertEquals(Prefs.STYLE_BIG, prefs.homeStyle)
        assertEquals(Layout.BIG_TWO, HomeSlots.layoutOf(prefs))
        assertEquals("map_center", prefs.homeLayout)
        val big = HomeSlots.toggle(HomeSlots.load(prefs, Layout.BIG_TWO), "r", Card.TRIPS).refused
        assertEquals("the media is alone", HomeSlots.Refusal.MEDIA_ALONE, big)
        HomeSlots.save(prefs, HomeSlots.toggle(HomeSlots.load(prefs, Layout.BIG_TWO), "l", Card.NAV).config)
        // Back to the normal mode: its layout and its cards
        prefs.homeStyle = 0
        assertEquals(Layout.MAP_CENTER, HomeSlots.layoutOf(prefs))
        assertEquals(normal, HomeSlots.load(prefs, Layout.MAP_CENTER))
        // And Grand affichage again: its own
        prefs.homeStyle = Prefs.STYLE_BIG
        assertEquals(Layout.BIG_TWO, HomeSlots.layoutOf(prefs))
        assertTrue(Card.NAV in HomeSlots.load(prefs, Layout.BIG_TWO).cards("l"))
        // A layout of the other mode stored by mistake is not taken
        prefs.homeLayoutBig = "full"; assertEquals(Layout.BIG_QUAD, HomeSlots.layoutOf(prefs))
        prefs.homeStyle = 0; prefs.homeLayout = "big_two"; assertEquals(Layout.FULL, HomeSlots.layoutOf(prefs))
        // A normal layout chosen leaves Grand affichage
        prefs.homeStyle = Prefs.STYLE_BIG
        HomeSlots.choose(prefs, Layout.FULL)
        assertEquals(0, prefs.homeStyle)
    }

    /** On screen, enlarged: the two columns, a band as wide as two quarters and the gap, a half as high as two. */
    @Test fun bigSpansLineUpOnScreen() {
        fun boxes(layout: Layout): Map<String, View> {
            prefs.raw.edit().clear().putBoolean("vertical_dock", true).putInt("home_style", Prefs.STYLE_BIG).putString("home_layout_big", layout.key).commit()
            val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
            @Suppress("UNCHECKED_CAST")
            val frames = MainActivity::class.java.getDeclaredField("frames").apply { isAccessible = true }.get(act) as Map<String, Any>
            return frames.mapValues { (_, f) -> f.javaClass.getDeclaredField("box").apply { isAccessible = true }.get(f) as View }
        }
        val gap = Math.round(12 * app.mg4dash.ui.BigDisplay.DPI / 160f)
        val quad = boxes(Layout.BIG_QUAD)
        assertEquals(setOf("tl", "tr", "bl", "br"), quad.keys)
        val q = quad.getValue("tl")
        assertTrue("four equal places", quad.values.all { Math.abs(it.width - q.width) <= 1 && Math.abs(it.height - q.height) <= 1 })
        assertTrue("two columns over the screen: ${q.width}", q.width in 800..900)
        val bands = boxes(Layout.BIG_BAND_BOTTOM)
        assertTrue("band ${bands.getValue("b").width} vs ${2 * q.width + gap}", Math.abs(bands.getValue("b").width - (2 * q.width + gap)) <= 2)
        assertTrue(Math.abs(bands.getValue("tl").width - q.width) <= 1)
        val left = boxes(Layout.BIG_LEFT)
        assertTrue("half ${left.getValue("l").height} vs ${2 * q.height + gap}", Math.abs(left.getValue("l").height - (2 * q.height + gap)) <= 2)
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
