package app.mg4dash

import android.content.res.Configuration
import android.os.Looper
import android.widget.TextView
import app.mg4dash.pages.ClimateActivity
import app.mg4dash.settings.SettingsActivity
import app.mg4dash.system.Prefs
import app.mg4dash.ui.BigDisplay
import app.mg4dash.ui.Themes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.TimeUnit

/** Grand affichage, step 1 (19/11): each enlarged screen has its own density, Android's own setting untouched. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "w1920dp-h720dp-land-mdpi")
class BigDisplayTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private val prefs get() = Prefs(ctx)
    private val big = BigDisplay.DPI / 160f

    @Before fun setUp() {
        prefs.raw.edit().putBoolean("vertical_dock", true).putBoolean("tutorial_shortcuts_seen", true).putInt("theme_mode", 1)
            .putInt("home_style", Prefs.STYLE_BIG).commit()
    }

    private fun <T : android.app.Activity> open(cls: Class<T>): T =
        Robolectric.buildActivity(cls).setup().get().also { repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) } }

    private fun density(c: android.content.Context) = c.resources.displayMetrics.density

    @Test fun eachScreenItsOwnDensity() {
        val home = open(MainActivity::class.java)
        assertEquals(BigDisplay.DPI, home.resources.configuration.densityDpi)
        assertEquals(big, density(home), 0.001f)
        assertEquals(big, home.resources.displayMetrics.scaledDensity, 0.001f)
        assertEquals(big, density(open(ClimateActivity::class.java)), 0.001f)
        assertEquals(big, density(open(app.mg4dash.apps.AppsActivity::class.java)), 0.001f)
        // Settings keeps its size, and so does Android: the application and the system are not touched
        assertEquals(1f, density(open(SettingsActivity::class.java)), 0.001f)
        assertEquals(1f, density(ctx), 0.001f)
        assertEquals(160, android.content.res.Resources.getSystem().configuration.densityDpi)
    }

    @Test fun offTheNormalSize() {
        prefs.homeStyle = 0
        assertEquals(1f, density(open(MainActivity::class.java)), 0.001f)
        assertEquals(1f, density(open(ClimateActivity::class.java)), 0.001f)
        // The old Map style (2, still stored on old installs) is not Grand affichage
        prefs.homeStyle = 2
        assertEquals(1f, density(open(MainActivity::class.java)), 0.001f)
    }

    /** Settings opened from the enlarged home: each keeps its own metrics (they were shared and reset in place). */
    @Test fun settingsOverTheEnlargedHomeDoesNotShrinkIt() {
        val home = open(MainActivity::class.java)
        val settings = open(SettingsActivity::class.java)
        assertEquals(1f, density(settings), 0.001f)
        assertEquals(big, density(home), 0.001f)
        // And the other way: a page opened after Settings is enlarged again, Settings stays as it is
        val page = open(ClimateActivity::class.java)
        assertEquals(big, density(page), 0.001f)
        assertEquals(1f, density(settings), 0.001f)
        assertEquals(big, density(home), 0.001f)
    }

    /** The enlarged screen reports 540 dp of height: the panel's real 720 count, or it was reduced a second time. */
    @Test fun noDoubleReduction() {
        prefs.raw.edit().putBoolean("fullscreen", false).commit()
        val home = open(MainActivity::class.java)
        assertTrue(home.resources.configuration.screenHeightDp in 530..545)
        assertEquals(big, density(home), 0.001f)
    }

    /** SAIC bars shown (#43): the reduction multiplies the enlargement, once. */
    @Config(qualifiers = "w1920dp-h640dp-land-mdpi")
    @Test fun barsReductionMultipliesTheFactor() {
        prefs.raw.edit().putBoolean("fullscreen", false).commit()
        assertEquals(big * 640 / 720f, density(open(MainActivity::class.java)), 0.002f)
        assertEquals(640 / 720f, density(open(SettingsActivity::class.java)), 0.002f)
    }

    /** What sits under MG's own buttons keeps its pixels. */
    @Test fun fogZoneSamePixels() {
        val home = open(MainActivity::class.java)
        for (dp in listOf(app.mg4dash.ui.Kit.FOG_SAFE_DP, 68, 6))
            assertEquals(dp.toFloat(), BigDisplay.unscaled(home, dp) * density(home), 1f)
        assertEquals(76, BigDisplay.unscaled(open(SettingsActivity::class.java), 76))
    }

    @Test fun toastsEnlarged() {
        app.mg4dash.ui.Toasts.show(ctx, "Grand affichage")
        val tv = ShadowToast.getLatestToast().view as TextView
        assertEquals(19f * big, tv.textSize, 0.5f)
        prefs.homeStyle = 0
        app.mg4dash.ui.Toasts.show(ctx, "Normal")
        assertEquals(19f, (ShadowToast.getLatestToast().view as TextView).textSize, 0.5f)
    }

    @Test fun windowsOffTheScreens() {
        assertEquals(big, density(Themes.themed(ctx)), 0.001f)
        // The maintenance banner keeps its size
        assertEquals(1f, density(Themes.themed(ctx, big = false)), 0.001f)
        prefs.homeStyle = 0
        assertEquals(1f, density(Themes.themed(ctx)), 0.001f)
    }

    /** Switching the mode rebuilds home and the open pages. */
    @Test fun modeInTheThemeSignature() {
        val on = Themes.signature(ctx)
        prefs.homeStyle = 0
        assertNotEquals(on, Themes.signature(ctx))
    }

    /** The known risk: the screen's own configuration with AppCompat must keep the theme and the night mode. */
    @Test fun themeAndNightModeKept() {
        for (mode in listOf(1, 2)) {
            prefs.raw.edit().putInt("theme_mode", mode).putInt("theme_bg", 3).putInt("theme_accent", 4).commit()
            prefs.homeStyle = Prefs.STYLE_BIG
            val home = open(MainActivity::class.java)
            prefs.homeStyle = 0
            val normal = open(MainActivity::class.java)
            for (res in listOf(R.color.panel, R.color.bg_top, R.color.accent_action, R.color.text_value))
                assertEquals("theme $mode, colour $res", Themes.color(normal, res), Themes.color(home, res))
            assertEquals(normal.resources.configuration.uiMode, home.resources.configuration.uiMode)
            assertEquals(ctx.resources.configuration.locales, home.resources.configuration.locales)
        }
    }

    /** Only the density and the sizes in dp are overridden: the car keeps driving the rest. */
    @Test fun overrideIsTheDensityOnly() {
        val base = Configuration(ctx.resources.configuration)
        val o = BigDisplay.override(base)
        assertEquals(213, o.densityDpi)
        assertEquals(1920 * 160 / 213, o.screenWidthDp)
        assertEquals(720 * 160 / 213, o.screenHeightDp)
        assertEquals(0, o.uiMode)
        assertEquals(0f, o.fontScale)
        assertTrue(o.locales.isEmpty)
        assertFalse(BigDisplay.factor(ctx) > 1f)
    }
}
