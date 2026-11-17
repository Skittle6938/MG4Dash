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

    /** Switched on from the tile: heating or cooling at once, from the last set point and outside temperature. */
    @Test fun shownAtOnceWhenSwitchedOn() {
        flow.value = flow.value.copy(hvacOn = false, acOn = true, driverTemp = 22f, outsideTemp = 4f)
        val off = ShortcutTiles.climate(ctx, flow.value)
        val next = ShortcutTiles.act(ctx, ShortcutTiles.Tile.CLIMATE, off)!!
        assertEquals(true, next.inverted)
        assertEquals(1, next.level)
        assertEquals(ctx.getString(R.string.sc_heating), next.label)
        assertEquals(R.drawable.ic_sc_clim_heat, ShortcutTiles.icon(ShortcutTiles.Tile.CLIMATE, next))
    }

    /** #122 animation: none within half a degree, stronger with the gap, full from about 14 °C. */
    @Test fun animationStrength() {
        assertEquals(0f, app.mg4dash.ui.ClimateFx.intensity(0.4f), 1e-6f)
        assertEquals(0.25f + 3f / 14f, app.mg4dash.ui.ClimateFx.intensity(3f), 1e-4f)
        assertEquals(app.mg4dash.ui.ClimateFx.intensity(3f), app.mg4dash.ui.ClimateFx.intensity(-3f), 1e-6f)
        assertEquals(1f, app.mg4dash.ui.ClimateFx.intensity(18f), 1e-6f)
    }

    private fun cardFx(option: Boolean, on: Boolean): app.mg4dash.ui.ClimateFx.Effect? {
        ctx.getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putBoolean("vertical_dock", true).putBoolean("tutorial_shortcuts_seen", true).putInt("theme_mode", 1).putBoolean("climate_fx", option).commit()
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        flow.value = flow.value.copy(hvacOn = on, acOn = true, driverTemp = 22f, outsideTemp = 4f)
        repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        val card = MainActivity::class.java.getDeclaredField("climatePanel").apply { isAccessible = true }.get(act) as android.view.View
        return card.foreground as? app.mg4dash.ui.ClimateFx.Effect
    }

    /** Heating: the card animates; the option off or the climate off: no animation, the mode's word stays with the option off. */
    @Test fun animationOnTheCardOnly() {
        assertEquals(true, cardFx(option = true, on = true)?.running)
        assertEquals(true, cardFx(option = false, on = true)?.running != true)
        assertEquals(true, cardFx(option = true, on = false)?.running != true)
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
