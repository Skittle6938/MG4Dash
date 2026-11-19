package app.mg4dash

import androidx.core.graphics.ColorUtils
import app.mg4dash.system.Prefs
import app.mg4dash.ui.Themes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Grand affichage, step 7: on every background, dark and light, the values reach 7:1 and the secondary texts and titles
 * 4.5:1 on the cards; the big numbers are regular instead of light. The colours with a meaning do not change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1920dp-h720dp-land-mdpi")
class BigContrastTest {
    private val prefs get() = Prefs(RuntimeEnvironment.getApplication())

    private fun page(big: Boolean, mode: Int, bg: Int): android.app.Activity {
        prefs.raw.edit().putBoolean("vertical_dock", true).putInt("theme_mode", mode).putInt("theme_bg", bg)
            .putInt("home_style", if (big) Prefs.STYLE_BIG else 0).commit()
        return Robolectric.buildActivity(app.mg4dash.pages.TripsActivity::class.java).setup().get()
    }

    /** The colour seen: [fg] over the card's panel, itself over the screen's background. */
    private fun contrast(a: android.app.Activity, fgRes: Int): Double {
        val bg = Themes.color(a, R.color.bg_top) or 0xFF000000.toInt()
        val panel = ColorUtils.compositeColors(Themes.color(a, R.color.panel), bg)
        return ColorUtils.calculateContrast(Themes.color(a, fgRes) or 0xFF000000.toInt(), panel)
    }

    @Test fun contrastOnEveryBackground() {
        for (mode in listOf(1, 2)) for (bg in 0..4) {
            val a = page(true, mode, bg)
            val tag = "mode $mode, background $bg"
            assertTrue("$tag: values ${contrast(a, R.color.text_value)}", contrast(a, R.color.text_value) >= 7.0)
            assertTrue("$tag: secondary ${contrast(a, R.color.text_sub)}", contrast(a, R.color.text_sub) >= 7.0)
            assertTrue("$tag: titles ${contrast(a, R.color.text_title)}", contrast(a, R.color.text_title) >= 4.5)
            assertTrue("$tag: greyed ${contrast(a, R.color.text_disabled)}", contrast(a, R.color.text_disabled) >= 3.0)
            // Stronger than the normal size
            val normal = page(false, mode, bg)
            assertTrue("$tag: titles stronger", contrast(a, R.color.text_title) > contrast(normal, R.color.text_title))
            assertNotEquals(Themes.color(normal, R.color.panel_border), Themes.color(a, R.color.panel_border))
            // The colours with a meaning stay
            assertEquals(Themes.color(normal, R.color.accent_action), Themes.color(a, R.color.accent_action))
            assertEquals(Themes.color(normal, R.color.text_value), Themes.color(a, R.color.text_value))
        }
    }

    @Test fun bigNumbersRegular() {
        val big = page(true, 1, 1)
        val normal = page(false, 1, 1)
        assertEquals(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL), Themes.valueFont(big))
        assertEquals(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL), Themes.valueFont(normal))
    }
}
