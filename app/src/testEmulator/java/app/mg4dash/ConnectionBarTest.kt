package app.mg4dash

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import app.mg4dash.system.Prefs
import app.mg4dash.ui.HomeSlots
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
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.TimeUnit

/** The connection bar's place (17/11): the weather by default, any place of cards chosen in the edit mode, or none. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class ConnectionBarTest {
    private val prefs get() = Prefs(RuntimeEnvironment.getApplication())
    private val full = HomeSlots.defaults(HomeSlots.Layout.FULL)

    @Test fun weatherByDefault() {
        // Nothing stored (existing users): the weather's place, as always
        assertNull(HomeSlots.connectionsChoice(prefs, HomeSlots.Layout.FULL))
        assertEquals("bc", HomeSlots.connectionsSlot(null, full))
    }

    @Test fun chosenPlaceKeptPerLayout() {
        HomeSlots.setConnectionsChoice(prefs, HomeSlots.Layout.FULL, "tl")
        assertEquals("tl", HomeSlots.connectionsChoice(prefs, HomeSlots.Layout.FULL))
        assertEquals("tl", HomeSlots.connectionsSlot("tl", full))
        // Another layout keeps its own (the weather)
        assertNull(HomeSlots.connectionsChoice(prefs, HomeSlots.Layout.MAP))
        HomeSlots.setConnectionsChoice(prefs, HomeSlots.Layout.FULL, null)
        assertNull(HomeSlots.connectionsChoice(prefs, HomeSlots.Layout.FULL))
    }

    @Test fun noneHidesIt() {
        assertNull(HomeSlots.connectionsSlot(HomeSlots.CONN_NONE, full))
    }

    @Test fun notOnTheMediaNorAMissingPlace() {
        // "tr" holds the media in the Full layout: back to the weather; an unknown place too
        assertFalse(HomeSlots.canHoldConnections(full, "tr"))
        assertTrue(HomeSlots.canHoldConnections(full, "tl"))
        // A small card (energy, bottom left) has no room to give up; the weather's small place does (beside the city)
        assertFalse(HomeSlots.canHoldConnections(full, "bl"))
        assertTrue(HomeSlots.canHoldConnections(full, "bc"))
        assertEquals("bc", HomeSlots.connectionsSlot("bl", full))
        assertEquals("bc", HomeSlots.connectionsSlot("tr", full))
        assertEquals("bc", HomeSlots.connectionsSlot("zz", full))
    }

    private fun shoot(name: String, edit: Boolean) {
        prefs.raw.edit().putBoolean("vertical_dock", true).putBoolean("tutorial_shortcuts_seen", true).putInt("theme_mode", 1)
            .putInt("home_style", 0).putString("home_layout", "full").commit()
        HomeSlots.setConnectionsChoice(prefs, HomeSlots.Layout.FULL, "tl")
        val intent = android.content.Intent(RuntimeEnvironment.getApplication(), MainActivity::class.java)
        if (edit) intent.putExtra(app.mg4dash.ui.CardsEditor.EXTRA_EDIT, true)
        val act = Robolectric.buildActivity(MainActivity::class.java, intent).setup().get()
        repeat(6) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        val root = act.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(File("build/screenshots").apply { mkdirs() }, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        HomeSlots.setConnectionsChoice(prefs, HomeSlots.Layout.FULL, null)
    }

    /** The bar at the top of the climate card (top left), its content below it. */
    @Test fun barInTheClimateCard() = shoot("connections_in_climate.png", edit = false)

    /** The edit mode with the "Connection bar here" button on each place. */
    @Test fun editModeButton() = shoot("connections_edit_mode.png", edit = true)

    // ── The bar belongs to the place: centred at its top, every card there makes room inside (17/11) ──

    private fun hostShots(place: String, prefix: String) {
        val layout = "full"
        prefs.raw.edit().putBoolean("vertical_dock", true).putBoolean("tutorial_shortcuts_seen", true).putInt("theme_mode", 1)
            .putInt("home_style", 0).putString("home_layout", layout)
            .putString("slots_$layout", "tl=climate,trip;tc=energy,consumption;tr=media;bl=power;bc=hourly,daily;br=phone").commit()
        HomeSlots.setConnectionsChoice(prefs, HomeSlots.Layout.FULL, place)
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        repeat(6) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        fun snap(name: String) {
            val root = act.window.decorView
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))
            File(File("build/screenshots").apply { mkdirs() }, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        snap("${prefix}_before_swipe.png")
        val frames = MainActivity::class.java.getDeclaredField("frames").apply { isAccessible = true }.get(act) as Map<*, *>
        val frame = frames[place]!!
        val box = frame.javaClass.getDeclaredField("box").apply { isAccessible = true }.get(frame) as android.view.ViewGroup
        val boxTop = box.top
        val swipe = MainActivity::class.java.declaredMethods.first { it.name == "swipeSlot" }.apply { isAccessible = true }
        swipe.invoke(act, frame, 1)
        repeat(4) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        snap("${prefix}_after_swipe.png")
        // Still in the same place, and the place has not moved
        val row = MainActivity::class.java.getDeclaredField("connections").apply { isAccessible = true }.get(act) as android.view.View
        assertTrue(row.parent === box)
        assertEquals(boxTop, box.top)
        HomeSlots.setConnectionsChoice(prefs, HomeSlots.Layout.FULL, null)
    }

    @Test fun barInClimatePlaceStaysOnSwipe() = hostShots("tl", "conn_climate")
    @Test fun barInEnergyPlaceStaysOnSwipe() = hostShots("tc", "conn_energy")

    // ── The old "connection icons" setting ──

    @Test fun oldSettingOffBecomesNoBar() {
        prefs.raw.edit().putBoolean("connection_icons", false).remove("conn_migrated").commit()
        HomeSlots.migrateConnections(prefs)
        HomeSlots.Layout.entries.forEach { assertEquals(HomeSlots.CONN_NONE, HomeSlots.connectionsChoice(prefs, it)) }
        assertTrue(prefs.connectionIcons)
        // Once only: a place chosen afterwards stays
        HomeSlots.setConnectionsChoice(prefs, HomeSlots.Layout.FULL, "tl")
        HomeSlots.migrateConnections(prefs)
        assertEquals("tl", HomeSlots.connectionsChoice(prefs, HomeSlots.Layout.FULL))
    }

    @Test fun oldSettingOnKeepsTheWeather() {
        prefs.raw.edit().putBoolean("connection_icons", true).remove("conn_migrated").commit()
        HomeSlots.migrateConnections(prefs)
        HomeSlots.Layout.entries.forEach { assertNull(HomeSlots.connectionsChoice(prefs, it)) }
    }

    // ── The widgets' own options ──

    private fun popupShot(method: String, name: String) {
        prefs.raw.edit().putBoolean("vertical_dock", true).putBoolean("tutorial_shortcuts_seen", true).putInt("theme_mode", 1)
            .putInt("home_style", 0).putString("home_layout", "full").commit()
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        repeat(4) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        MainActivity::class.java.getDeclaredMethod(method).apply { isAccessible = true }.invoke(act)
        repeat(2) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()!!
        val v = dialog.window!!.decorView
        val bmp = Bitmap.createBitmap(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File(File("build/screenshots").apply { mkdirs() }, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun energyOptionsPopup() = popupShot("energyOptions", "popup_energy_options.png")
    @Test fun mapOptionsPopup() = popupShot("mapOptions", "popup_map_options.png")

    /** Settings > Home, lighter. */
    @Test fun homeSettings() {
        prefs.raw.edit().putBoolean("vertical_dock", true).putBoolean("tutorial_shortcuts_seen", true).putInt("theme_mode", 1).commit()
        val act = Robolectric.buildActivity(app.mg4dash.settings.SettingsActivity::class.java,
            android.content.Intent().putExtra(app.mg4dash.settings.SettingsActivity.EXTRA_TAB, 0)).setup().get()
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        val root = act.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(File("build/screenshots").apply { mkdirs() }, "settings_home_light.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
