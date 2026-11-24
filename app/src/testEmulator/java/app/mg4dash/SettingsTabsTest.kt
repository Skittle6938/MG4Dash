package app.mg4dash

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.mg4dash.settings.SettingsActivity
import app.mg4dash.system.Prefs
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * The five tabs (24/11): every setting of the old layout is in one of them, exactly once (none lost, none twice),
 * and a tab saved before opens the same settings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class SettingsTabsTest {
    private val prefs get() = Prefs(RuntimeEnvironment.getApplication())
    private fun idle() = repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS) }
    private fun texts(v: View): List<String> = if (v is ViewGroup) (0 until v.childCount).flatMap { texts(v.getChildAt(it)) } else listOfNotNull((v as? TextView)?.text?.toString()?.trim())

    /** The settings of the old Home, Appearance, Vehicle, 4G data and System tabs still in the Settings page. */
    private val settings = listOf(
        R.string.ly_edit, R.string.st_cards_offered, R.string.tu_show, R.string.st_bar, R.string.st_wide_bar, R.string.st_shortcuts_menu,
        R.string.st_fullscreen, R.string.st_call_bar, R.string.st_theme_mode, R.string.st_night_sun, R.string.st_theme_smooth, R.string.st_panel_outline,
        R.string.st_theme_bg, R.string.st_theme_accent, R.string.st_start_source, R.string.st_start_playback, R.string.st_aa_button,
        R.string.st_sound_boot_trigger, R.string.st_sound_volume, R.string.st_units, R.string.st_pressure, R.string.st_clock, R.string.st_own_keyboard,
        R.string.du_last30, R.string.du_detail_open, R.string.st_replace, R.string.st_valet, R.string.st_update_auto, R.string.st_update_beta,
        R.string.un_button, R.string.bk_save, R.string.bk_restore, R.string.bk_history, R.string.bk_token, R.string.st_compat_run, R.string.st_log,
        R.string.st_adb_start,
    )

    @Test fun everySettingOnce() {
        prefs.raw.edit().clear().putBoolean("vertical_dock", true).putInt("theme_mode", 1).commit()
        val a = Robolectric.buildActivity(SettingsActivity::class.java).setup().get().also { idle() }
        val all = texts(a.window.decorView)
        for (id in settings) {
            val label = a.getString(id, "").substringBefore("%").trimEnd(' ', ':', ' ', ' ', '…', '›')
            assertEquals("« $label » once", 1, all.count { it == label || it.startsWith(label) && (it.length - label.length) <= 3 })
        }
    }

    /** A tab saved before the five tabs opens the same settings (Vehicle and 4G data: General). */
    @Test fun savedTabsFollow() {
        for ((old, new) in listOf(0 to 0, 1 to 1, 2 to 3, 3 to 3, 4 to 4)) {
            prefs.raw.edit().clear().putBoolean("vertical_dock", true).putBoolean("settings_tabs_v2", true).putBoolean("settings_tabs_v3", true)
                .putInt("settings_tab", old).commit()
            Robolectric.buildActivity(SettingsActivity::class.java).setup().get(); idle()
            assertEquals("old tab $old", new, prefs.raw.getInt("settings_tab", -1))
        }
    }

    /** Each tab in dark and light, as the driver sees it (the page scrolls when a column is taller than the screen). */
    @org.junit.experimental.categories.Category(Screenshot::class)
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun shots() {
        for ((theme, look) in listOf(1 to "dark", 2 to "light")) for (t in 0..4) {
            prefs.raw.edit().clear().putBoolean("vertical_dock", true).putInt("theme_mode", theme).putBoolean("settings_tabs_v2", true)
                .putBoolean("settings_tabs_v3", true).putBoolean("settings_tabs_v4", true).putInt("settings_tab", t).commit()
            val a = Robolectric.buildActivity(SettingsActivity::class.java).setup().get().also { idle() }
            val root = a.window.decorView
            val bmp = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888).also { root.draw(android.graphics.Canvas(it)) }
            java.io.File(java.io.File("build/screenshots/tabs").apply { mkdirs() }, "tab${t}_$look.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
