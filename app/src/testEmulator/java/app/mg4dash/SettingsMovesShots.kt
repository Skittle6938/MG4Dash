package app.mg4dash

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import app.mg4dash.cards.HomeCard
import app.mg4dash.settings.SettingsActivity
import app.mg4dash.system.Prefs
import app.mg4dash.ui.HomeSlots.Card
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Settings moved to the cards and pages they belong to (24/11), and the pickers in the settings' look: the weather
 * card's options, the driving card's whole window, the Trips page, the shortcut and phone pickers, the Vehicle and
 * Connections tabs. Written to build/screenshots/moves/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
@org.junit.experimental.categories.Category(Screenshot::class)
class SettingsMovesShots {
    private val prefs get() = Prefs(RuntimeEnvironment.getApplication())
    private fun idle(n: Int = 3) = repeat(n) { shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS) }
    private val looks = listOf(1 to "dark", 2 to "light")

    private fun save(bmp: Bitmap, name: String) =
        File(File("build/screenshots/moves").apply { mkdirs() }, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

    private fun page(a: Activity, name: String) {
        val root = a.window.decorView
        save(Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }, name)
    }

    private fun window(a: Activity, name: String) {
        idle(2)
        val dlg = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing } ?: return
        val w = dlg.window!!
        val dv = w.decorView
        val width = if (w.attributes.width > 0) w.attributes.width else a.window.decorView.width
        dv.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(a.window.decorView.height, View.MeasureSpec.AT_MOST))
        dv.layout(0, 0, dv.measuredWidth, dv.measuredHeight)
        save(Bitmap.createBitmap(dv.measuredWidth, dv.measuredHeight, Bitmap.Config.ARGB_8888).also { dv.draw(Canvas(it)) }, name)
        dlg.dismiss(); idle(1)
    }

    private fun base(theme: Int) = prefs.raw.edit().clear().putBoolean("vertical_dock", true).putBoolean("tutorial_shortcuts_seen", true).putInt("theme_mode", theme)

    @Suppress("UNCHECKED_CAST")
    private fun cards(a: MainActivity) = MainActivity::class.java.getDeclaredField("homeCards").apply { isAccessible = true }.get(a) as Map<Card, HomeCard>

    @Test fun cardWindows() {
        for ((theme, look) in looks) {
            base(theme).putInt("home_style", 0).putString("home_layout", "full")
                .putString("slots_full", "tl=hourly;tc=consumption,map;tr=shortcuts;bl=energy;bc=climate;br=power,trip,phone").commit()
            val a = Robolectric.buildActivity(MainActivity::class.java).setup().get().also { idle() }
            for (card in listOf(Card.HOURLY, Card.CLIMATE, Card.ENERGY, Card.CONSUMPTION, Card.SHORTCUTS, Card.POWER)) {
                ShadowDialog.reset()
                cards(a)[card]?.view?.performLongClick(); window(a, "options_${card.id}_$look.png")
            }
            ShadowDialog.reset()
            app.mg4dash.ui.DrivingCard.openAll(a); window(a, "driving_all_$look.png")
            ShadowDialog.reset()
            MainActivity::class.java.getDeclaredMethod("mapMenu", kotlin.jvm.functions.Function0::class.java).apply { isAccessible = true }.invoke(a, null)
            window(a, "map_menu_$look.png")
            ShadowDialog.reset()
            app.mg4dash.settings.ProfileWindow.edit(a, app.mg4dash.system.Profiles.current(a)) {}; window(a, "profile_$look.png")
            ShadowDialog.reset()
            app.mg4dash.ui.CardsEditor.askUrl(a, "") {}; window(a, "web_address_$look.png")
            ShadowDialog.reset()
            app.mg4dash.shortcuts.Shortcuts.edit(a, 0) {}; window(a, "shortcut_menu_$look.png")
            ShadowDialog.reset()
            app.mg4dash.shortcuts.Shortcuts.pickApp(a, "Application") {}; window(a, "shortcut_app_$look.png")
            ShadowDialog.reset()
            app.mg4dash.shortcuts.PhoneFavorites.edit(a, 0) {}; window(a, "phone_menu_$look.png")
        }
    }

    @Test fun energyPage() {
        for ((theme, look) in looks) {
            base(theme).commit()
            val e = Robolectric.buildActivity(app.mg4dash.pages.EnergyActivity::class.java).setup().get().also { idle() }
            page(e, "energy_$look.png")
            // The battery's settings (24/11), from its section on the Energy page
            ShadowDialog.reset()
            runCatching { app.mg4dash.pages.EnergyActivity::class.java.getDeclaredMethod("batteryOptions", kotlin.jvm.functions.Function0::class.java)
                .apply { isAccessible = true }.invoke(e, { }) }
            window(e, "battery_$look.png")
        }
    }

    @Test fun pages() {
        for ((theme, look) in looks) {
            base(theme).putBoolean("settings_tabs_v2", true).putInt("settings_tab", 2).commit()
            page(Robolectric.buildActivity(SettingsActivity::class.java).setup().get().also { idle() }, "page_car_$look.png")
            base(theme).commit()
            // Home Assistant: a window like the others (24/11)
            ShadowDialog.reset()
            Robolectric.buildActivity(SettingsActivity::class.java).setup().get().also { app.mg4dash.settings.HaWindow.show(it); idle(); window(it, "ha_$look.png") }
            // The 24 colours' grid (accent), the current one circled
            base(theme).putInt("theme_accent", 0).commit()
            val s = Robolectric.buildActivity(SettingsActivity::class.java).setup().get().also { idle() }
            ShadowDialog.reset()
            SettingsActivity::class.java.getDeclaredMethod("colorGrid", IntArray::class.java, Int::class.javaPrimitiveType, String::class.java, kotlin.jvm.functions.Function1::class.java)
                .apply { isAccessible = true }.invoke(s, app.mg4dash.ui.Themes.ACCENT_SWATCHES, 0, s.getString(R.string.st_theme_accent), { _: Int -> })
            window(s, "color_grid_accent_$look.png")
            ShadowDialog.reset()
            SettingsActivity::class.java.getDeclaredMethod("colorGrid", IntArray::class.java, Int::class.javaPrimitiveType, String::class.java, kotlin.jvm.functions.Function1::class.java)
                .apply { isAccessible = true }.invoke(s, app.mg4dash.ui.Themes.backgroundDots(s), 1, s.getString(R.string.st_theme_bg), { _: Int -> })
            window(s, "color_grid_bg_$look.png")
            base(theme).putBoolean("settings_tabs_v2", true).putBoolean("settings_tabs_v3", true).putInt("settings_tab", 1).commit()
            page(Robolectric.buildActivity(SettingsActivity::class.java).setup().get().also { idle() }, "page_look_$look.png")
            base(theme).putBoolean("settings_tabs_v2", true).putBoolean("settings_tabs_v3", true).putInt("settings_tab", 0).commit()
            page(Robolectric.buildActivity(SettingsActivity::class.java).setup().get().also { idle() }, "page_home_$look.png")
            base(theme).putBoolean("settings_tabs_v2", true).putBoolean("settings_tabs_v3", true).putInt("settings_tab", 4).commit()
            page(Robolectric.buildActivity(SettingsActivity::class.java).setup().get().also { idle() }, "page_system_$look.png")
            base(theme).putFloat("kwh_price", 0.25f).commit()
            Robolectric.buildActivity(app.mg4dash.pages.TripsActivity::class.java).setup().get().also { idle() }.let { t ->
                ShadowDialog.reset()
                app.mg4dash.pages.TripsActivity::class.java.getDeclaredMethod("confirmClear").apply { isAccessible = true }.invoke(t)
                window(t, "trips_clear_$look.png")
            }
            base(theme).putFloat("kwh_price", 0.25f).commit()
            page(Robolectric.buildActivity(app.mg4dash.pages.TripsActivity::class.java).setup().get().also { idle() }, "trips_$look.png")
        }
    }
}
