package app.mg4dash

import app.mg4dash.settings.SettingsActivity
import app.mg4dash.ui.Themes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The 24 colours (24/11) and the contract with MG4Radio: "mg4dash_theme" starts with the background's and the
 * buttons' numbers (0 to 23), the same numbers in both apps; every number gives a theme in dark and in light.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ThemePaletteTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun prefs() = app.getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE)

    @Test fun twentyFourOfEach() {
        assertEquals(24, Themes.BACKGROUNDS.size); assertEquals(24, Themes.BACKGROUNDS_LIGHT.size); assertEquals(24, Themes.BACKGROUND_SWATCHES.size)
        assertEquals(24, Themes.ACCENTS.size); assertEquals(24, Themes.ACCENTS_LIGHT.size); assertEquals(24, Themes.ACCENT_SWATCHES.size)
        assertEquals(24, (Themes.BACKGROUNDS + Themes.BACKGROUNDS_LIGHT + Themes.ACCENTS + Themes.ACCENTS_LIGHT).toSet().size / 4)
    }

    /** Every number is published as it is (fields 1 and 2), and read back the same. */
    @Test fun everyNumberRoundTrips() {
        for (i in 0 until 24) {
            prefs().edit().putInt("theme_bg", i).putInt("theme_accent", 23 - i).commit()
            val f = Themes.published(app).split(",")
            assertEquals(12, f.size)
            assertEquals(i, f[0].toInt()); assertEquals(23 - i, f[1].toInt())
        }
    }

    /** Every theme applies, in dark and in light, and gives its own button colour. */
    @Test fun everyThemeApplies() {
        for (mode in listOf(1, 2)) for (i in 0 until 24) {
            prefs().edit().putInt("theme_mode", mode).putInt("theme_bg", i).putInt("theme_accent", i).commit()
            val a = Robolectric.buildActivity(SettingsActivity::class.java).create().get()
            val idle = Themes.color(a, R.color.button_idle)
            assertTrue("bg $i mode $mode", idle != 0)
            assertEquals("accent $i mode $mode", Themes.attr(a, R.attr.mgAction, 0) != 0, true)
        }
    }

    /** The signature tells every pair apart (it used to add them up). */
    @Test fun signaturesDiffer() {
        val seen = mutableSetOf<Int>()
        for (b in 0 until 24) for (c in 0 until 24) {
            prefs().edit().putInt("theme_bg", b).putInt("theme_accent", c).commit()
            seen += Themes.signature(app)
        }
        assertEquals(24 * 24, seen.size)
    }
}
