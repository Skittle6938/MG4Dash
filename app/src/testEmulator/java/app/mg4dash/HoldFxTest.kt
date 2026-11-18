package app.mg4dash

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import app.mg4dash.car.CarBridge
import app.mg4dash.car.VehicleState
import app.mg4dash.ui.ClimateFx
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.TimeUnit

/** The Keep climate screen's warm / cold effect (Marc, 18/11, variant B): the climate card's, at screen scale. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class HoldFxTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @Test fun shownOnlyWhileTheClimateRuns() {
        // Heating, as strong as on the card
        val heat = ClimateFx.holdEffect(true, true, true, true, true, 22f, 4f)!!
        assertEquals(true, heat.first)
        assertEquals(ClimateFx.intensity(18f), heat.second, 1e-6f)
        assertEquals(false, ClimateFx.holdEffect(true, true, true, true, true, 19f, 31f)!!.first)
        // Waiting for its time, climate off, screen off, option off, a temperature unknown, no gap: nothing
        assertNull(ClimateFx.holdEffect(false, true, true, true, true, 22f, 4f))
        assertNull(ClimateFx.holdEffect(true, false, true, true, true, 22f, 4f))
        assertNull(ClimateFx.holdEffect(true, true, false, true, true, 22f, 4f))
        assertNull(ClimateFx.holdEffect(true, true, true, false, true, 22f, 4f))
        assertNull(ClimateFx.holdEffect(true, true, true, true, true, null, 4f))
        assertNull(ClimateFx.holdEffect(true, true, true, true, true, 21f, 21.2f))
    }

    @Suppress("UNCHECKED_CAST")
    private fun shot(set: Float, out: Float, name: String): app.mg4dash.climate.ClimateHoldActivity {
        val flow = CarBridge::class.java.getDeclaredField("_state").apply { isAccessible = true }.get(CarBridge) as MutableStateFlow<VehicleState>
        ctx.getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit().putInt("theme_mode", 1)
            .putBoolean("climhold_enabled", true).putInt("climhold_max_min", 0).putBoolean("climhold_scheduled", false).commit()
        val home = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        flow.value = flow.value.copy(hvacOn = true, acOn = true, driverTemp = set, outsideTemp = out)
        app.mg4dash.climate.ClimateHold.start(home, 0, 0)
        val act = Robolectric.buildActivity(app.mg4dash.climate.ClimateHoldActivity::class.java).setup().get()
        repeat(6) { Thread.sleep(200); shadowOf(Looper.getMainLooper()).idleFor(1000, TimeUnit.MILLISECONDS) }
        flow.value = flow.value.copy(hvacOn = true, acOn = true, driverTemp = set, outsideTemp = out)
        repeat(2) { shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS) }
        val root = act.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(File("build/mockhold").apply { mkdirs() }, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return act
    }

    private fun effectOf(act: app.mg4dash.climate.ClimateHoldActivity) =
        act.javaClass.getDeclaredField("fx").apply { isAccessible = true }.get(act) as ClimateFx.Effect

    @Category(Screenshot::class)
    @Test fun screenHeating() {
        val act = shot(22f, 4f, "real_B_warm.png")
        assertTrue(effectOf(act).running)
        app.mg4dash.climate.ClimateHold.stop("test")
    }

    @Category(Screenshot::class)
    @Test fun screenCooling() {
        val act = shot(19f, 31f, "real_B_cold.png")
        assertTrue(effectOf(act).running)
        app.mg4dash.climate.ClimateHold.stop("test")
    }
}
