package app.mg4dash

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.mg4dash.system.Prefs
import app.mg4dash.ui.HomeSlots
import app.mg4dash.ui.HomeSlots.Card
import app.mg4dash.ui.HomeSlots.Layout
import app.mg4dash.ui.ShortcutTiles
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

/** Grand affichage, step 8: three home styles, each keeps its layout and cards; the tile goes back to the right style. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class BigStylesTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private val prefs get() = Prefs(ctx)

    @Test fun roundTripsLoseNothing() {
        prefs.raw.edit().clear().commit()
        HomeSlots.choose(prefs, Layout.MAP_CENTER)
        val standard = HomeSlots.toggle(HomeSlots.load(prefs, Layout.MAP_CENTER), "tl", Card.TRIPS).config
        HomeSlots.save(prefs, standard)
        HomeSlots.choose(prefs, Layout.BIG_LEFT)
        val big = HomeSlots.toggle(HomeSlots.load(prefs, Layout.BIG_LEFT), "tr", Card.NAV).config
        HomeSlots.save(prefs, big)
        prefs.homeStyle = 1
        // Simple → Standard → Grand affichage → Simple: each finds its own again
        prefs.homeStyle = 0
        assertEquals(Layout.MAP_CENTER, HomeSlots.layoutOf(prefs)); assertEquals(standard, HomeSlots.load(prefs, Layout.MAP_CENTER))
        prefs.homeStyle = Prefs.STYLE_BIG
        assertEquals(Layout.BIG_LEFT, HomeSlots.layoutOf(prefs)); assertEquals(big, HomeSlots.load(prefs, Layout.BIG_LEFT))
        // Each style's layout is known whatever the style in use (the two menus of Settings)
        prefs.homeStyle = 1
        assertEquals(Layout.MAP_CENTER, HomeSlots.layoutOf(prefs, big = false))
        assertEquals(Layout.BIG_LEFT, HomeSlots.layoutOf(prefs, big = true))
    }

    @Test fun tileGoesBackToTheStyleBefore() {
        prefs.raw.edit().clear().commit()
        for (before in listOf(0, 1)) {
            prefs.homeStyle = before
            assertFalse(ShortcutTiles.read(ctx, ShortcutTiles.Tile.BIG).inverted)
            HomeSlots.toggleBig(prefs)
            assertEquals(Prefs.STYLE_BIG, prefs.homeStyle)
            assertTrue("the tile lit", ShortcutTiles.lit(ShortcutTiles.Tile.BIG, ShortcutTiles.read(ctx, ShortcutTiles.Tile.BIG)))
            HomeSlots.toggleBig(prefs)
            assertEquals("back to $before", before, prefs.homeStyle)
        }
        // Grand affichage chosen in Settings (no style remembered): back to Standard
        prefs.raw.edit().clear().putInt("home_style", Prefs.STYLE_BIG).commit()
        HomeSlots.toggleBig(prefs)
        assertEquals(0, prefs.homeStyle)
    }

    @Test fun tileOfferedInTheMenu() {
        assertTrue(ShortcutTiles.Tile.entries.any { it.id == "big" })
        assertEquals(ShortcutTiles.Tile.BIG, ShortcutTiles.order(listOf("fog", "big")).last())
    }

    /** Settings › Home: Standard ▾, Grand affichage ▾ and Simple, the style in use selected. */
    @Test fun threeStyleButtons() {
        fun texts(v: View, out: MutableList<TextView> = mutableListOf()): List<TextView> {
            if (v is TextView) out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
            return out
        }
        for ((style, label) in listOf(0 to "Standard", Prefs.STYLE_BIG to "Grand affichage", 1 to "Épuré")) {
            prefs.raw.edit().clear().putBoolean("vertical_dock", true).putInt("home_style", style).putInt("settings_tab", 0).commit()
            val a = Robolectric.buildActivity(app.mg4dash.settings.SettingsActivity::class.java).setup().get()
            repeat(2) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
            val all = texts(a.window.decorView)
            val buttons = listOf("Standard", "Grand affichage", "Épuré").map { name -> all.first { it.text.toString().trim().removeSuffix("▾").trim() == name } }
            buttons.forEach { b -> assertEquals("$label: ${b.text}", b.text.toString().startsWith(label), b.isSelected) }
            // Settings keeps its normal size in every style
            assertEquals(1f, a.resources.displayMetrics.density, 0.001f)
        }
    }
}
