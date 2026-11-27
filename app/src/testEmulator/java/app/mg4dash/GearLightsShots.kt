package app.mg4dash

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import app.mg4dash.pages.VehicleActivity
import app.mg4dash.showroom.CarLook
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

/** The Vehicle page's gear, parking brake telltale and lamps (27/11): build/screenshots/gear_*.png. */
@org.junit.experimental.categories.Category(Screenshot::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class GearLightsShots {
    private fun save(root: View, name: String) {
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1920, 720)
        val bmp = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(File("build/screenshots").apply { mkdirs() }, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun settle(root: View, ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS); save(root, "x.png"); Thread.sleep(50) }
    }

    @Test fun gearAndLights() {
        val ctx = RuntimeEnvironment.getApplication()
        // name, vertical bar, theme (1 dark, 2 light), three-quarter, gear, lights
        val cases = listOf(
            Triple("q34_dark_P_off", listOf(true, 1, true), 1 to false),
            Triple("q34_dark_D_on", listOf(true, 1, true), 4 to true),
            Triple("side_dark_P_off", listOf(true, 1, false), 1 to false),
            Triple("side_dark_D_on", listOf(true, 1, false), 4 to true),
            Triple("q34_light_P_on", listOf(true, 2, true), 1 to true),
            Triple("side_light_D_on", listOf(true, 2, false), 4 to true),
            Triple("q34_bottom_dark_R_on", listOf(false, 1, true), 2 to true),
            Triple("side_bottom_dark_N_off", listOf(false, 1, false), 3 to false),
            Triple("q34_dark_unreadable_on", listOf(true, 1, true), null to true),
        )
        for ((name, o, gl) in cases) {
            app.mg4dash.system.Prefs(ctx).raw.edit().clear().putBoolean("vertical_dock", o[0] as Boolean).putInt("theme_mode", o[1] as Int)
                .putBoolean("tutorial_shortcuts_seen", true).commit()
            CarLook(ctx).stock(); CarLook(ctx).threeQuarter = o[2] as Boolean
            VehicleActivity.MOCK_GEAR = gl.first; VehicleActivity.MOCK_LIGHTS = gl.second
            val a = Robolectric.buildActivity(VehicleActivity::class.java).setup().visible().get()
            val root = a.window.decorView
            settle(root, 6_000)
            save(root, "gear_$name.png")
        }
        VehicleActivity.MOCK_GEAR = 1; VehicleActivity.MOCK_LIGHTS = false
    }
}
