package app.mg4dash

import app.mg4dash.ui.Themes
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The theme Radio Dash reads: "bg,accent,light,vertical,0,wide,right,am,fog". Its bar follows MG4Dash's width and
 * side (Radio Dash #2), the 8th field turns its AM band on (off by default), the 9th says MG's fog light button is
 * hidden by the shortcuts menu (Radio Dash's left bar then takes the full height).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ThemePublishTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun prefs() = app.getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE)
    /** vertical, 0, wide, right */
    private fun bar(f: List<String>) = f.subList(3, 7)

    @Test fun narrowBarOnTheLeft() {
        prefs().edit().putBoolean("vertical_dock", true).putBoolean("wide_bar", false).putInt("bar_side", 1).commit()
        assertEquals(listOf("1", "0", "0", "0"), bar(Themes.published(app).split(",")))
    }

    @Test fun wideBarOnTheRight() {
        prefs().edit().putBoolean("vertical_dock", true).putBoolean("wide_bar", true).putInt("bar_side", 2).commit()
        val f = Themes.published(app).split(",")
        assertEquals(9, f.size)
        assertEquals(listOf("1", "0", "1", "1"), bar(f))
    }

    @Test fun amBandOffByDefault() {
        assertEquals("0", Themes.published(app).split(",")[7])
        prefs().edit().putBoolean("radio_dash_am", true).commit()
        assertEquals("1", Themes.published(app).split(",")[7])
    }

    @Test fun fogButtonHiddenWithTheShortcutsMenu() {
        prefs().edit().putBoolean("shortcuts_menu", false).commit()
        assertEquals("0", Themes.published(app).split(",")[8])
        prefs().edit().putBoolean("shortcuts_menu", true).commit()
        assertEquals("1", Themes.published(app).split(",")[8])
    }
}
