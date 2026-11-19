package app.mg4dash

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import app.mg4dash.system.Prefs
import org.junit.Before
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

/** Grand affichage renders on the 1920 x 720 px panel (build/screenshots/big_*.png). */
@org.junit.experimental.categories.Category(Screenshot::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class BigDisplayShots {
    private val prefs get() = Prefs(RuntimeEnvironment.getApplication())

    @Before fun bigOn() {
        prefs.raw.edit().putBoolean("vertical_dock", true).putBoolean("tutorial_shortcuts_seen", true).putInt("theme_mode", 1)
            .putInt("home_style", Prefs.STYLE_BIG).commit()
    }

    private fun idle(n: Int = 5) = repeat(n) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }

    /** The panel's pixels, whatever the screen's dp. */
    private fun save(root: View, name: String) {
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1920, 720)
        val bmp = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(File("build/screenshots").apply { mkdirs() }, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun <T : android.app.Activity> shoot(cls: Class<T>, name: String) {
        val a = Robolectric.buildActivity(cls).setup().get(); idle()
        save(a.window.decorView, name)
    }

    @Test fun home() = shoot(MainActivity::class.java, "big_home.png")
    @Test fun homeNormalForComparison() { prefs.homeStyle = 0; shoot(MainActivity::class.java, "big_home_off.png") }

    /** Settings opened from the enlarged home: Settings at its normal size, home behind still enlarged. */
    @Test fun settingsFromHome() {
        val home = Robolectric.buildActivity(MainActivity::class.java).setup().get(); idle()
        shoot(app.mg4dash.settings.SettingsActivity::class.java, "big_settings.png")
        save(home.window.decorView, "big_home_after_settings.png")
    }

    /** The seven layouts of Grand affichage with their starting contents (step 2), and their menu. */
    @Test fun sevenLayouts() {
        for (layout in app.mg4dash.ui.HomeSlots.Layout.of(big = true)) {
            prefs.raw.edit().putString("home_layout_big", layout.key).commit()
            shoot(MainActivity::class.java, "big_layout_${layout.key}.png")
        }
    }

    /**
     * Step 3: each main card in each form of place (quarter, half, band), in French, English, German and Turkish (the
     * longest words), dark and light.
     */
    @Test fun cardForms() {
        val sets = listOf(
            "quarters" to ("big_quad" to "tl=climate;bl=energy;tr=media;br=hourly"),
            "quarters2" to ("big_quad" to "tl=shortcuts;bl=trip;tr=media;br=daily"),
            "halves" to ("big_two" to "l=climate;r=media"),
            "halves2" to ("big_two" to "l=energy;r=hourly"),
            "bands" to ("big_two_bands" to "t=climate;b=media"),
            "bands2" to ("big_two_bands" to "t=energy;b=hourly"),
            "bands3" to ("big_two_bands" to "t=shortcuts;b=media"),
        )
        for (lang in listOf("fr", "en", "de", "tr")) for (theme in listOf(1, 2)) {
            if (theme == 2 && lang != "fr") continue
            RuntimeEnvironment.setQualifiers("$lang-w1920dp-h720dp-land-mdpi")
            for ((name, spec) in sets) {
                prefs.raw.edit().putInt("theme_mode", theme).putString("home_layout_big", spec.first).putString("slots_" + spec.first, spec.second).commit()
                shoot(MainActivity::class.java, "big_form_${name}_$lang${if (theme == 2) "_light" else ""}.png")
            }
        }
    }

    @Test fun climatePage() = shoot(app.mg4dash.pages.ClimateActivity::class.java, "big_page_climate.png")
    @Test fun vehiclePage() = shoot(app.mg4dash.pages.VehicleActivity::class.java, "big_page_vehicle.png")

    /** The shortcuts menu, pulled down from the top. */
    @Test fun shortcutsMenu() {
        prefs.raw.edit().putBoolean("fullscreen", false).commit()
        app.mg4dash.ui.ShortcutsPanel.init(RuntimeEnvironment.getApplication())
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get(); idle()
        val cb = a.window.callback
        fun touch(action: Int, y: Float, t: Long) = cb.dispatchTouchEvent(android.view.MotionEvent.obtain(0, t, action, 960f, y, 0))
        touch(android.view.MotionEvent.ACTION_DOWN, 10f, 0)
        var y = 10f
        for (i in 1..12) { y += 40f; touch(android.view.MotionEvent.ACTION_MOVE, y, i * 16L) }
        touch(android.view.MotionEvent.ACTION_UP, y, 300)
        repeat(12) { Thread.sleep(150); idle(1) }
        save(a.window.decorView, "big_shortcuts_menu.png")
    }
}
