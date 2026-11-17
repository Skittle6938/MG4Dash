package app.mg4dash

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import app.mg4dash.car.CarBridge
import app.mg4dash.car.VehicleState
import app.mg4dash.ui.ShortcutTiles
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

/** #122: the climate card and page show heating or cooling as the shortcut tile does. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class ClimateModeTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    @Suppress("UNCHECKED_CAST")
    private val flow get() = CarBridge::class.java.getDeclaredField("_state").apply { isAccessible = true }.get(CarBridge) as MutableStateFlow<VehicleState>

    @Test fun modeAsTheTile() {
        val heat = ShortcutTiles.climateMode(ctx, VehicleState(hvacOn = true, acOn = true, driverTemp = 22f, outsideTemp = 4f))!!
        assertEquals(R.drawable.ic_sc_clim_heat, heat.first)
        assertEquals(ctx.getString(R.string.sc_heating), heat.second)
        val cool = ShortcutTiles.climateMode(ctx, VehicleState(hvacOn = true, acOn = true, driverTemp = 20f, outsideTemp = 31f))!!
        assertEquals(ctx.getString(R.string.sc_cooling), cool.second)
        assertNull(ShortcutTiles.climateMode(ctx, VehicleState(hvacOn = false, driverTemp = 22f, outsideTemp = 4f)))
    }

    private fun snap(root: android.view.View, name: String) {
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(File("build/screenshots").apply { mkdirs() }, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun heating() { flow.value = flow.value.copy(hvacOn = true, acOn = true, driverTemp = 22f, outsideTemp = 4f) }

    @Test fun cardHeating() {
        ctx.getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putBoolean("vertical_dock", true).putBoolean("tutorial_shortcuts_seen", true).putInt("theme_mode", 1).commit()
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        heating()
        repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        snap(act.window.decorView, "climate_mode_card.png")
    }

    @Test fun pageHeating() {
        ctx.getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putBoolean("vertical_dock", true).putInt("theme_mode", 1).commit()
        val act = Robolectric.buildActivity(app.mg4dash.pages.ClimateActivity::class.java).setup().get()
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        heating()
        repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        snap(act.window.decorView, "climate_mode_page.png")
    }
}
