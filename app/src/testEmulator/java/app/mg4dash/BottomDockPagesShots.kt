package app.mg4dash

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import app.mg4dash.showroom.CarImage
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

/** Vehicle and Energy pages with the bottom dock and the side bar, in Italian (#141): build/screenshots/dock141_*.png. */
@org.junit.experimental.categories.Category(Screenshot::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "it-w1920dp-h720dp-land-mdpi")
class BottomDockPagesShots {
    private fun all(v: View): List<View> = listOf(v) + if (v is android.view.ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()

    private fun settle(root: View, ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)
            save(root, "x.png")
            Thread.sleep(50)
        }
    }

    private fun save(root: View, name: String) {
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1920, 720)
        val bmp = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(File("build/screenshots").apply { mkdirs() }, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // Everything the pages show filled in (as MockCar), so that each column is at its tallest
    private val state = app.mg4dash.car.VehicleState(
        connected = true, outsideTemp = 13f, batteryPercent = 99, socPrecise = 99.9f, rangeKm = 324, chargeStatus = 3,
        chargeCtrl = 2, dischargeCtrl = 2, driveMode = 3, regenLevel = 3, onePedal = 0, odometerKm = 43680, speedKmh = 0f,
        ecoOn = false, steeringMode = 2, brakePedalMode = 0, enduranceMode = 0, lasMode = 3, fcwState = 2, fcwBrakeMode = 2,
        tsr = 1, udw = 1,
    )

    @Test fun pages() {
        val ctx = RuntimeEnvironment.getApplication()
        for ((n, vertical) in listOf("bottom" to false, "side" to true)) {
            app.mg4dash.system.Prefs(ctx).raw.edit().clear().putBoolean("vertical_dock", vertical).putInt("theme_mode", 1)
                .putBoolean("tutorial_shortcuts_seen", true).commit()
            CarLook(ctx).stock(); CarLook(ctx).threeQuarter = false
            for ((p, cls) in listOf("energy" to app.mg4dash.pages.EnergyActivity::class.java, "vehicle" to app.mg4dash.pages.VehicleActivity::class.java)) {
                val a = Robolectric.buildActivity(cls).setup().get()
                val root = a.window.decorView
                settle(root, 6_000)
                a.render(state)
                settle(root, 1_500)
                save(root, "dock141_${p}_$n.png")
            }
            CarLook(ctx).threeQuarter = true
            val a = Robolectric.buildActivity(app.mg4dash.pages.VehicleActivity::class.java).setup().get()
            val root = a.window.decorView
            settle(root, 6_000); a.render(state); settle(root, 1_500)
            save(root, "dock141_vehicle34_$n.png")
        }
    }
}
